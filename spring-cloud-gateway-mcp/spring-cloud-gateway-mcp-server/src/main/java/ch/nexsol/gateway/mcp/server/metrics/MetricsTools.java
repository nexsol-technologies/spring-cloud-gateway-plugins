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

package ch.nexsol.gateway.mcp.server.metrics;

import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The tools over the metrics plugin: the traffic per route and the runtime of each
 * instance, read through the source the plugin selected. Every snapshot carries its
 * coverage, so the client knows whether the figures are this instance's or the fleet's.
 */
public class MetricsTools {

	static final String NO_INSTANCE_SOURCE = "no instance metrics source available";

	private final RouteMetricsSource routes;

	private final ObjectProvider<InstanceMetricsSource> instances;

	public MetricsTools(RouteMetricsSource routes, ObjectProvider<InstanceMetricsSource> instances) {
		this.routes = routes;
		this.instances = instances;
	}

	@McpTool(name = "route_traffic",
			description = "The traffic of every route: request count, average and maximum latency in milliseconds, "
					+ "client (4xx) and server (5xx) error counts and rates. The coverage field says whether the "
					+ "figures are this instance's only or consolidated across the instances.",
			annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
	public Mono<RouteMetricsSnapshot> routeTraffic() {
		return this.routes.collect();
	}

	@McpTool(name = "gateway_instances",
			description = "The runtime of each gateway instance the metrics source can see: uptime, JVM heap, "
					+ "garbage collection and threads, process and system CPU, open files, Netty event loops and "
					+ "the connection pools towards the services.",
			annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
	public Mono<InstanceMetricsSnapshot> gatewayInstances() {
		InstanceMetricsSource source = this.instances.getIfAvailable();
		return (source != null) ? source.collect() : Mono.just(InstanceMetricsSnapshot.empty(NO_INSTANCE_SOURCE));
	}

}
