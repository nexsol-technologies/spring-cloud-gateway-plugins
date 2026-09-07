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

package ch.nexsol.gateway.ui.insights;

import java.util.ArrayList;
import java.util.List;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

/**
 * The instances an introspection view can be pointed at.
 * <p>
 * The list is the one the runtime view already draws, so whichever provider the metrics
 * plugin resolved &mdash; discovery, Prometheus, Redis &mdash; is the one that answers
 * here too, and no second directory has to be configured. Without that plugin, or before
 * any other instance has reported, the list holds this instance alone.
 * <p>
 * It is also the allow-list. A view names the instance it wants by id and never by
 * address, and {@link #resolve(String)} only ever returns an address this directory
 * reported: a client that could be handed an arbitrary URL would make the gateway fetch
 * whatever a caller asked it to.
 */
public class ActuatorInstances {

	/**
	 * The id a view names when it means every instance rather than one. A logging level
	 * is a property of the deployment, so this is what the loggers view asks for by
	 * default.
	 */
	public static final String ALL = "*";

	private final ObjectProvider<InstanceMetricsSource> metricsSource;

	private final InstanceIdentity identity;

	/**
	 * Creates the directory over the (optional) instance metrics source.
	 * @param metricsSource the provider over the source the instances are read from
	 * @param identity the identity of the running instance
	 */
	public ActuatorInstances(ObjectProvider<InstanceMetricsSource> metricsSource, InstanceIdentity identity) {
		this.metricsSource = metricsSource;
		this.identity = identity;
	}

	/**
	 * Lists the instances a view may be pointed at, this one always first.
	 * @return the known instances, never empty
	 */
	public Mono<List<Instance>> list() {
		InstanceMetricsSource source = this.metricsSource.getIfAvailable();
		if (source == null) {
			return Mono.just(List.of(local()));
		}
		return source.collect().map((snapshot) -> {
			List<Instance> instances = new ArrayList<>();
			instances.add(local());
			for (InstanceMetric metric : snapshot.instances()) {
				// The instance answering is already first, and an instance whose address
				// no source knows cannot be reached over HTTP whatever its id says.
				if (!this.identity.id().equals(metric.instanceId()) && StringUtils.hasText(metric.uri())) {
					instances.add(new Instance(metric.instanceId(), metric.uri(), false));
				}
			}
			return List.copyOf(instances);
		}).onErrorReturn(List.of(local()));
	}

	/**
	 * Resolves the address of one instance, by the id a view named it with.
	 * @param instanceId the id of the instance, {@code null} or unknown for this one
	 * @return the address to read the Actuator endpoints at, empty for this instance
	 */
	public Mono<Instance> resolve(String instanceId) {
		if (!StringUtils.hasText(instanceId) || this.identity.id().equals(instanceId)) {
			return Mono.just(local());
		}
		return list().map((instances) -> instances.stream()
			.filter((instance) -> instance.id().equals(instanceId))
			.findFirst()
			// An id this directory does not know is read as this instance rather than
			// refused: the instance it named may simply have gone away since the page
			// was drawn, and the view still has something true to show.
			.orElseGet(this::local));
	}

	private Instance local() {
		return new Instance(this.identity.id(), null, true);
	}

	/**
	 * One instance an introspection view can read.
	 *
	 * @param id the identifier of the instance
	 * @param uri where its Actuator endpoints are reachable, {@code null} for the
	 * instance answering the console
	 * @param self whether this is the instance answering the console
	 */
	public record Instance(String id, String uri, boolean self) {

	}

}
