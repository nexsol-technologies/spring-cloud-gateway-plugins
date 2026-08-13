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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.nexsol.gateway.metrics.RouteMetric;
import ch.nexsol.gateway.metrics.RouteMetricsAggregator;
import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Consolidates the figures of every instance registered under the gateway's own service
 * id, by asking each of them what it counted.
 * <p>
 * No infrastructure beyond the service registry already in place, at the cost of one call
 * per instance per refresh, and of losing what an instance counted when it is replaced.
 * <p>
 * Each instance is polled on the path served by {@link LocalRouteMetricsController},
 * which always answers with its own meter registry: polling the consolidated endpoint
 * instead would have every instance poll every other one, forever.
 * <p>
 * An instance that fails or times out is left out rather than failing the whole reading,
 * and the coverage says over how many instances the figures were actually computed.
 */
public class DiscoveryRouteMetricsSource implements RouteMetricsSource {

	private static final Logger LOG = LoggerFactory.getLogger(DiscoveryRouteMetricsSource.class);

	private static final ParameterizedTypeReference<List<RouteMetric>> METRIC_LIST = new ParameterizedTypeReference<>() {
	};

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
	public DiscoveryRouteMetricsSource(ObjectProvider<ReactiveDiscoveryClient> discoveryClient, WebClient webClient,
			DiscoveryMetricsProperties properties, String serviceId) {
		this.discoveryClient = discoveryClient;
		this.webClient = webClient;
		this.properties = properties;
		this.serviceId = serviceId;
	}

	@Override
	public Mono<RouteMetricsSnapshot> collect() {
		ReactiveDiscoveryClient registry = this.discoveryClient.getIfAvailable();
		if (registry == null) {
			// The classpath carries a discovery client but the application registered
			// none, discovery being turned off. Say it rather than quietly reporting the
			// figures of whichever instance answered, which is the very thing this source
			// exists to avoid.
			return Mono.just(RouteMetricsSnapshot.empty("service discovery is not enabled"));
		}
		// Deferred so the counters belong to one subscription: created at assembly time
		// they would be shared by every subscriber of the returned mono, and a second
		// reading would report twice the instances.
		return Mono.defer(() -> {
			AtomicInteger reached = new AtomicInteger();
			AtomicInteger discovered = new AtomicInteger();
			return registry.getInstances(this.serviceId)
				.doOnNext((instance) -> discovered.incrementAndGet())
				.flatMap((instance) -> poll(instance).doOnNext((metrics) -> reached.incrementAndGet()))
				.flatMapIterable((metrics) -> metrics)
				.collectList()
				.map((partials) -> new RouteMetricsSnapshot(coverage(reached.get(), discovered.get()),
						RouteMetricsAggregator.merge(partials)));
		}).onErrorResume((ex) -> {
			LOG.warn("Could not list the instances of '{}': {}", this.serviceId, ex.getMessage());
			return Mono.just(RouteMetricsSnapshot.empty("service discovery unavailable"));
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

	private Flux<List<RouteMetric>> poll(ServiceInstance instance) {
		return this.webClient.get()
			.uri(instance.getUri().resolve(this.properties.getPath()))
			.retrieve()
			.bodyToMono(METRIC_LIST)
			.timeout(this.properties.getTimeout())
			.onErrorResume((ex) -> {
				// One unreachable instance must not cost the figures of all the others.
				LOG.warn("Leaving instance {} out of the route metrics: {}", instance.getUri(), ex.getMessage());
				return Mono.empty();
			})
			.flux();
	}

}
