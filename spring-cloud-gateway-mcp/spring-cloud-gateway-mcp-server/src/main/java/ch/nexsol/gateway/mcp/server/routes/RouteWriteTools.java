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

package ch.nexsol.gateway.mcp.server.routes;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.mcp.server.WriteGuard;
import ch.nexsol.gateway.mcp.server.routes.RouteTools.Step;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.StringUtils;

/**
 * The tools that change the route table, through the {@link RouteDefinitionWriter} of the
 * gateway: the database of the routes-database plugin when it is present, the in-memory
 * repository of the gateway otherwise, where a saved route lasts until the next restart.
 * Each change asks the gateway to rebuild its route table, so it takes effect at once.
 */
public class RouteWriteTools {

	private final RouteDefinitionWriter writer;

	private final ApplicationEventPublisher publisher;

	private final WriteGuard guard;

	public RouteWriteTools(RouteDefinitionWriter writer, ApplicationEventPublisher publisher, WriteGuard guard) {
		this.writer = writer;
		this.publisher = publisher;
		this.guard = guard;
	}

	@McpTool(name = "save_route",
			description = "Creates or replaces a route definition in the writable route source of the gateway and "
					+ "rebuilds the route table. Check the factories with list_filter_factories and "
					+ "list_predicate_factories first: a name that matches none fails the whole route table.",
			annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
					openWorldHint = false))
	public Mono<CallToolResult> saveRoute(
			@McpToolParam(description = "The route: id, target URI, order, predicates and filters as {name, args}, "
					+ "metadata") RouteSpec route,
			@McpToolParam(description = "true to perform the change; without it the call only describes it",
					required = false) Boolean confirm) {
		return this.guard.run(confirm, "save the route '" + route.routeId() + "' towards " + route.uri(), () -> {
			RouteDefinition definition = definition(route);
			return this.writer.save(Mono.just(definition))
				.then(refresh())
				.thenReturn("Route '" + route.routeId() + "' saved; the route table is being rebuilt.");
		});
	}

	@McpTool(name = "delete_route",
			description = "Deletes a route definition from the writable route source of the gateway and rebuilds the "
					+ "route table. A route declared by another source (configuration, files) cannot be deleted "
					+ "this way.",
			annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true,
					openWorldHint = false))
	public Mono<CallToolResult> deleteRoute(@McpToolParam(description = "The id of the route to delete") String routeId,
			@McpToolParam(description = "true to perform the change; without it the call only describes it",
					required = false) Boolean confirm) {
		return this.guard.run(confirm, "delete the route '" + routeId + "'",
				() -> this.writer.delete(Mono.just(routeId))
					.then(refresh())
					.thenReturn("Route '" + routeId + "' deleted; the route table is being rebuilt."));
	}

	@McpTool(name = "refresh_routes",
			description = "Asks the gateway to read its route sources again and rebuild the route table, as the "
					+ "/actuator/gateway/refresh endpoint does.",
			annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
					openWorldHint = false))
	public Mono<CallToolResult> refreshRoutes(
			@McpToolParam(description = "true to perform the change; without it the call only describes it",
					required = false) Boolean confirm) {
		return this.guard.run(confirm, "rebuild the route table from every source",
				() -> refresh().thenReturn("The route table is being rebuilt."));
	}

	private Mono<Void> refresh() {
		return Mono.fromRunnable(() -> this.publisher.publishEvent(new RefreshRoutesEvent(this)));
	}

	static RouteDefinition definition(RouteSpec route) {
		if (!StringUtils.hasText(route.routeId()) || !StringUtils.hasText(route.uri())) {
			throw new IllegalArgumentException("A route needs a routeId and a uri");
		}
		RouteDefinition definition = new RouteDefinition();
		definition.setId(route.routeId());
		definition.setUri(URI.create(route.uri()));
		if (route.order() != null) {
			definition.setOrder(route.order());
		}
		List<PredicateDefinition> predicates = new ArrayList<>();
		for (Step step : (route.predicates() != null) ? route.predicates() : List.<Step>of()) {
			PredicateDefinition predicate = new PredicateDefinition();
			predicate.setName(step.name());
			predicate.setArgs(args(step));
			predicates.add(predicate);
		}
		definition.setPredicates(predicates);
		List<FilterDefinition> filters = new ArrayList<>();
		for (Step step : (route.filters() != null) ? route.filters() : List.<Step>of()) {
			FilterDefinition filter = new FilterDefinition();
			filter.setName(step.name());
			filter.setArgs(args(step));
			filters.add(filter);
		}
		definition.setFilters(filters);
		if (route.metadata() != null) {
			definition.setMetadata(new LinkedHashMap<>(route.metadata()));
		}
		return definition;
	}

	private static Map<String, String> args(Step step) {
		return (step.args() != null) ? new LinkedHashMap<>(step.args()) : new LinkedHashMap<>();
	}

	/**
	 * A route to save, in the shape list_routes returns.
	 *
	 * @param routeId the route id
	 * @param uri the target URI, {@code http://}, {@code https://} or {@code lb://}
	 * @param order the order among the routes, 0 when omitted. The optional components
	 * are marked nullable: the schema the server validates a call against would otherwise
	 * require every one of them
	 * @param predicates the predicates, each a factory name and its arguments
	 * @param filters the filters, each a factory name and its arguments
	 * @param metadata the route metadata
	 */
	public record RouteSpec(String routeId, String uri, @Nullable Integer order, @Nullable List<Step> predicates,
			@Nullable List<Step> filters, @Nullable Map<String, Object> metadata) {
	}

}
