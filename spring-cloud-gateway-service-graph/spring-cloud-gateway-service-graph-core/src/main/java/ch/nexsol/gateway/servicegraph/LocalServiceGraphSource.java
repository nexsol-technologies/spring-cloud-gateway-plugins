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

package ch.nexsol.gateway.servicegraph;

import java.util.ArrayList;
import java.util.List;

import ch.nexsol.gateway.commons.InstanceIdentity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;

import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.CALLER_TAG;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.CALLS_METER;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.OUTCOME_TAG;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.ROUTE_TAG;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.SERVER_ERROR;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.SERVICE_TAG;

/**
 * Reads the graph from the counters {@link ServiceGraphFilter} filled in the meter
 * registry of the running instance.
 * <p>
 * This is the graph of one JVM. Behind a load balancer it is the share of the traffic
 * that instance served, not the traffic &mdash; and a sticky load balancer makes it a
 * biased share, since a given caller always lands on the same instance. That is why the
 * snapshot is labelled with the instance it came from, and why the provider modules
 * exist. The {@link MeterRegistry} is resolved lazily and optionally: when none is
 * present the source reports no graph instead of failing.
 */
public class LocalServiceGraphSource implements ServiceGraphSource {

	private final ObjectProvider<MeterRegistry> meterRegistry;

	private final String coverage;

	/**
	 * Creates the source reading from the (optional) meter registry.
	 * @param meterRegistry the provider over the application meter registry
	 * @param identity the identity of the running instance
	 */
	public LocalServiceGraphSource(ObjectProvider<MeterRegistry> meterRegistry, InstanceIdentity identity) {
		this.meterRegistry = meterRegistry;
		this.coverage = "this instance only (" + identity.id() + ")";
	}

	@Override
	public Mono<ServiceGraphSnapshot> collect() {
		return Mono.fromSupplier(() -> ServiceGraphSnapshot.of(this.coverage, read()));
	}

	/**
	 * Reads the counters of this instance as one partial edge per counter, left to be
	 * merged. Exposed so a provider consolidating several instances can reuse the local
	 * reading as its own contribution.
	 * @return the partial edges held by this instance
	 */
	public List<GraphEdge> read() {
		MeterRegistry registry = this.meterRegistry.getIfAvailable();
		if (registry == null) {
			return List.of();
		}
		List<GraphEdge> edges = new ArrayList<>();
		for (Counter counter : registry.find(CALLS_METER).counters()) {
			String caller = counter.getId().getTag(CALLER_TAG);
			String service = counter.getId().getTag(SERVICE_TAG);
			String route = counter.getId().getTag(ROUTE_TAG);
			if (caller == null || service == null || route == null) {
				continue;
			}
			long calls = (long) counter.count();
			boolean failed = SERVER_ERROR.equals(counter.getId().getTag(OUTCOME_TAG));
			edges.add(new GraphEdge(caller, service, route, calls, failed ? calls : 0));
		}
		return edges;
	}

}
