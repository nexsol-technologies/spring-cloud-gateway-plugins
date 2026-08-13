/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.nexsol.gateway.metrics.discovery;

import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Reads the technical figures of every registered instance by asking each of them.
 * <p>
 * This is the source that fits these figures best, which is the reverse of the route
 * figures: a per-instance reading is native to the instance, so the registry hands out
 * exactly one row per instance, live, with no identity to reconstruct.
 * <p>
 * The rows are never merged &mdash; each instance is a line of its own &mdash; so unlike
 * the route fan-out this one only concatenates what it collected.
 */
public class DiscoveryInstanceMetricsSource implements InstanceMetricsSource {

	private static final Logger LOG = LoggerFactory.getLogger(DiscoveryInstanceMetricsSource.class);

	private final ObjectProvider<ReactiveDiscoveryClient> discoveryClient;

	private final WebClient webClient;

	private final DiscoveryMetricsProperties properties;

	private final String serviceId;

	/**
	 * Creates the source fanning out over the registered instances.
	 * @param discoveryClient the provider over the registry the sibling instances are
	 * listed from
	 * @param webClient the client used to poll them
	 * @param properties the discovery configuration
	 * @param serviceId the id this gateway is registered under
	 */
	public DiscoveryInstanceMetricsSource(ObjectProvider<ReactiveDiscoveryClient> discoveryClient, WebClient webClient,
			DiscoveryMetricsProperties properties, String serviceId) {
		this.discoveryClient = discoveryClient;
		this.webClient = webClient;
		this.properties = properties;
		this.serviceId = serviceId;
	}

	@Override
	public Mono<InstanceMetricsSnapshot> collect() {
		ReactiveDiscoveryClient registry = this.discoveryClient.getIfAvailable();
		if (registry == null) {
			// The classpath carries a discovery client but the application registered
			// none. Say it rather than quietly reporting the single instance that
			// answered, which is the very thing this source exists to avoid.
			return Mono.just(InstanceMetricsSnapshot.empty("service discovery is not enabled"));
		}
		// Deferred so the counters belong to one subscription: created at assembly time
		// they would be shared by every subscriber of the returned mono, and a second
		// reading would report twice the instances.
		return Mono.defer(() -> {
			AtomicInteger reached = new AtomicInteger();
			AtomicInteger discovered = new AtomicInteger();
			return registry.getInstances(this.serviceId)
				.doOnNext((instance) -> discovered.incrementAndGet())
				.flatMap((instance) -> poll(instance).doOnNext((metric) -> reached.incrementAndGet()))
				.sort(Comparator.comparing(InstanceMetric::instanceId))
				.collectList()
				.map((instances) -> new InstanceMetricsSnapshot(coverage(reached.get(), discovered.get()), instances));
		}).onErrorResume((ex) -> {
			LOG.warn("Could not list the instances of '{}': {}", this.serviceId, ex.getMessage());
			return Mono.just(InstanceMetricsSnapshot.empty("service discovery unavailable"));
		});
	}

	private String coverage(int reached, int discovered) {
		if (discovered == 0) {
			return "no instance registered as '" + this.serviceId + "'";
		}
		if (reached < discovered) {
			return reached + " of " + discovered + " instances (the others did not answer)";
		}
		return reached + ((reached == 1) ? " instance" : " instances") + ", consolidated";
	}

	private Flux<InstanceMetric> poll(ServiceInstance instance) {
		return this.webClient.get()
			.uri(instance.getUri().resolve(this.properties.getInstancePath()))
			.retrieve()
			.bodyToMono(InstanceMetric.class)
			// An instance does not know where it is reachable from; the registry does,
			// so the address is stamped here rather than guessed there.
			.map((metric) -> withUri(metric, instance.getUri().toString()))
			.timeout(this.properties.getTimeout())
			.onErrorResume((ex) -> {
				// One unreachable instance must not cost the figures of all the others.
				LOG.warn("Leaving instance {} out of the instance metrics: {}", instance.getUri(), ex.getMessage());
				return Mono.empty();
			})
			.flux();
	}

	private static InstanceMetric withUri(InstanceMetric metric, String uri) {
		return new InstanceMetric(metric.instanceId(), uri, metric.uptimeSeconds(), metric.jvm(), metric.system(),
				metric.netty(), metric.pools(), metric.instrumentation());
	}

}
