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

package ch.nexsol.gateway.mcp.server.resources;

import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.mcp.server.routes.RouteTools;
import ch.nexsol.gateway.mcp.server.routes.RouteTools.FactoryDescription;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.mcp.annotation.McpResource;

/**
 * The route table as resources a client can read into its context without calling a tool:
 * the whole inventory, one route, and the factories a route may name. Each is the JSON
 * the matching tool answers with.
 */
public class GatewayResources {

	private final RouteTools routes;

	private final JsonMapper json = JsonMapper.builder().build();

	public GatewayResources(RouteTools routes) {
		this.routes = routes;
	}

	@McpResource(uri = "gateway://routes", name = "routes", title = "The routes of the gateway",
			description = "Every route definition with the source it came from, as list_routes answers",
			mimeType = "application/json")
	public Mono<String> routes() {
		return this.routes.listRoutes().map(this.json::writeValueAsString);
	}

	@McpResource(uri = "gateway://routes/{routeId}", name = "route", title = "One route of the gateway",
			description = "One route definition, by id, as get_route answers", mimeType = "application/json")
	public Mono<String> route(String routeId) {
		return this.routes.getRoute(routeId).map(this.json::writeValueAsString);
	}

	@McpResource(uri = "gateway://factories", name = "factories", title = "The filter and predicate factories",
			description = "The filters and predicates a route may name, each with the fields of its shorthand",
			mimeType = "application/json")
	public Mono<String> factories() {
		return Mono.zip(this.routes.listFilterFactories(), this.routes.listPredicateFactories())
			.map((both) -> this.json.writeValueAsString(Map.of("filters", (List<FactoryDescription>) both.getT1(),
					"predicates", (List<FactoryDescription>) both.getT2())));
	}

}
