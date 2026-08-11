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

package ch.nexsol.gateway.ui.metrics;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetric.PoolStats;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the instances view: the technical health of every gateway instance, read from
 * whatever {@link InstanceMetricsSource} the metrics plugin resolved.
 */
@Controller
@RequestMapping("/ui/metrics/instances")
public class InstanceMetricsController {

	/** Reported when the metrics plugin resolved no instance source at all. */
	static final String NO_SOURCE = "no instance metrics source available";

	private final ObjectProvider<InstanceMetricsSource> instanceMetricsSource;

	private final ObjectProvider<PoolRouteResolver> poolRouteResolver;

	/**
	 * Creates the controller over the (optional) active instance metrics source.
	 * @param instanceMetricsSource the provider over the source the per-instance figures
	 * are read from
	 * @param poolRouteResolver the provider over the resolver naming the routes a
	 * connection pool serves, absent when the gateway exposes no route table
	 */
	public InstanceMetricsController(ObjectProvider<InstanceMetricsSource> instanceMetricsSource,
			ObjectProvider<PoolRouteResolver> poolRouteResolver) {
		this.instanceMetricsSource = instanceMetricsSource;
		this.poolRouteResolver = poolRouteResolver;
	}

	/**
	 * Renders the instances page inside the shell.
	 * @param model the view model
	 * @return the page view name
	 */
	@GetMapping
	public String page(Model model) {
		model.addAttribute("activeNav", "instances");
		return "dashboard/instances";
	}

	/**
	 * Returns the current per-instance figures and their coverage. A view whose source is
	 * absent reports that it has nothing to show rather than breaking the page.
	 * <p>
	 * The routes behind the connection pools are resolved here rather than by the source:
	 * the route table and the service registry describe the cluster, not the instance the
	 * figures came from, so the same names apply to every source.
	 * @return the current snapshot, with the routes the pools serve
	 */
	@GetMapping("/data")
	@ResponseBody
	public Mono<InstanceMetricsView> data() {
		InstanceMetricsSource source = this.instanceMetricsSource.getIfAvailable();
		Mono<InstanceMetricsSnapshot> snapshot = (source != null) ? source.collect()
				: Mono.just(InstanceMetricsSnapshot.empty(NO_SOURCE));
		return snapshot.flatMap(this::withRoutes);
	}

	private Mono<InstanceMetricsView> withRoutes(InstanceMetricsSnapshot snapshot) {
		PoolRouteResolver resolver = this.poolRouteResolver.getIfAvailable();
		if (resolver == null) {
			return Mono.just(new InstanceMetricsView(snapshot.coverage(), snapshot.instances(), Map.of()));
		}
		return resolver.routesByAddress(downstreamAddresses(snapshot))
			.map((routes) -> new InstanceMetricsView(snapshot.coverage(), snapshot.instances(), routes));
	}

	private static Set<String> downstreamAddresses(InstanceMetricsSnapshot snapshot) {
		Set<String> addresses = new LinkedHashSet<>();
		for (InstanceMetric instance : snapshot.instances()) {
			for (PoolStats pool : instance.pools()) {
				addresses.add(pool.remoteAddress());
			}
		}
		return addresses;
	}

}
