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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ch.nexsol.gateway.commons.routes.RouteDefinitionSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.support.ShortcutConfigurable;
import org.springframework.util.StringUtils;

/**
 * The tools over the route table: the definitions every source contributed, named after
 * the source, and the factories a route may name. A source that cannot be read is
 * reported next to the routes of the others rather than failing the whole answer: the
 * client learns which routes it does not see.
 */
public class RouteTools {

	static final Duration SOURCE_TIMEOUT = Duration.ofSeconds(5);

	private static final Logger LOG = LoggerFactory.getLogger(RouteTools.class);

	private final ObjectProvider<RouteDefinitionLocator> locators;

	private final ObjectProvider<GatewayFilterFactory<?>> filterFactories;

	private final ObjectProvider<RoutePredicateFactory<?>> predicateFactories;

	public RouteTools(ObjectProvider<RouteDefinitionLocator> locators,
			ObjectProvider<GatewayFilterFactory<?>> filterFactories,
			ObjectProvider<RoutePredicateFactory<?>> predicateFactories) {
		this.locators = locators;
		this.filterFactories = filterFactories;
		this.predicateFactories = predicateFactories;
	}

	@McpTool(name = "list_routes",
			description = "Every route definition of the gateway: id, target URI, order, predicates and filters with "
					+ "their arguments, metadata, and the source it came from (Properties, Files, Database, "
					+ "Openapi...). Call it first: the route ids it returns are what the other tools take. A source "
					+ "that could not be read is listed under unreadableSources with the reason; its routes are "
					+ "missing from the answer.",
			annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
	public Mono<Inventory> listRoutes() {
		return Flux.fromIterable(RouteDefinitionSources.sources(this.locators))
			.flatMapSequential(this::read)
			.collectList()
			.map(RouteTools::inventory);
	}

	@McpTool(name = "get_route", description = "One route definition, by id.",
			annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
	public Mono<RouteDescription> getRoute(
			@McpToolParam(description = "The route id, as listed by list_routes") String routeId) {
		if (!StringUtils.hasText(routeId)) {
			return Mono.error(new IllegalArgumentException("routeId is required"));
		}
		return listRoutes().flatMap((inventory) -> inventory.routes()
			.stream()
			.filter((route) -> routeId.equals(route.routeId()))
			.findFirst()
			.map(Mono::just)
			.orElseGet(() -> Mono.error(new IllegalArgumentException(notFound(routeId, inventory)))));
	}

	@McpTool(name = "list_filter_factories",
			description = "The gateway filters a route may name, each with the fields its shorthand form takes, in "
					+ "order. Use it to write a route's filters, or to check the ones a route declares.",
			annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
	public Mono<List<FactoryDescription>> listFilterFactories() {
		return Mono.fromSupplier(() -> this.filterFactories.orderedStream()
			.map((factory) -> describe(factory.name(), factory))
			.toList());
	}

	@McpTool(name = "list_predicate_factories",
			description = "The predicates a route may name, each with the fields its shorthand form takes, in order.",
			annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
	public Mono<List<FactoryDescription>> listPredicateFactories() {
		return Mono.fromSupplier(() -> this.predicateFactories.orderedStream()
			.map((factory) -> describe(factory.name(), factory))
			.toList());
	}

	private Mono<SourceRoutes> read(RouteDefinitionLocator locator) {
		String source = RouteDefinitionSources.name(locator);
		return RouteDefinitionSources.read(locator, SOURCE_TIMEOUT)
			.map((definitions) -> new SourceRoutes(
					definitions.stream().map((definition) -> describe(definition, source)).toList(), null))
			.onErrorResume((ex) -> {
				LOG.warn("Route definition source {} could not be read", source, ex);
				return Mono.just(new SourceRoutes(List.of(), new UnreadableSource(source, message(ex))));
			});
	}

	private static Inventory inventory(List<SourceRoutes> read) {
		List<RouteDescription> routes = new ArrayList<>();
		List<UnreadableSource> unreadable = new ArrayList<>();
		for (SourceRoutes source : read) {
			routes.addAll(source.routes());
			if (source.failure() != null) {
				unreadable.add(source.failure());
			}
		}
		return new Inventory(routes, unreadable);
	}

	private static String notFound(String routeId, Inventory inventory) {
		String message = "No route is defined with id '" + routeId + "'";
		if (inventory.unreadableSources().isEmpty()) {
			return message;
		}
		return message + "; it may belong to a source that could not be read: "
				+ inventory.unreadableSources()
					.stream()
					.map(UnreadableSource::source)
					.collect(Collectors.joining(", "));
	}

	private static RouteDescription describe(RouteDefinition definition, String source) {
		return new RouteDescription(definition.getId(),
				(definition.getUri() != null) ? definition.getUri().toString() : null, definition.getOrder(),
				definition.getPredicates().stream().map((p) -> new Step(p.getName(), p.getArgs())).toList(),
				definition.getFilters().stream().map((f) -> new Step(f.getName(), f.getArgs())).toList(),
				definition.getMetadata(), source);
	}

	private static FactoryDescription describe(String name, ShortcutConfigurable factory) {
		return new FactoryDescription(name, factory.shortcutFieldOrder());
	}

	private static String message(Throwable ex) {
		return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
	}

	/**
	 * The routes every source could contribute, and the sources that could not.
	 *
	 * @param routes the route definitions, in source order
	 * @param unreadableSources the sources that could not be read, empty when every
	 * source answered
	 */
	public record Inventory(List<RouteDescription> routes, List<UnreadableSource> unreadableSources) {
	}

	/**
	 * A source that could not be read.
	 *
	 * @param source the name of the source
	 * @param error why it could not be read
	 */
	public record UnreadableSource(String source, String error) {
	}

	/**
	 * A route definition, as one source contributed it.
	 *
	 * @param routeId the route id
	 * @param uri the target URI, {@code null} when the definition carries none
	 * @param order the order among the routes
	 * @param predicates the predicates, in order
	 * @param filters the filters, in order
	 * @param metadata the route metadata
	 * @param source the name of the source the definition came from
	 */
	public record RouteDescription(String routeId, String uri, int order, List<Step> predicates, List<Step> filters,
			Map<String, Object> metadata, String source) {
	}

	/**
	 * A predicate or a filter of a route: the factory it names and the arguments it
	 * passes.
	 *
	 * @param name the factory name
	 * @param args the arguments, by name
	 */
	public record Step(String name, Map<String, String> args) {
	}

	/**
	 * A filter or predicate factory a route may name.
	 *
	 * @param name the name a route uses
	 * @param shortcutFields the fields the shorthand form takes, in order
	 */
	public record FactoryDescription(String name, List<String> shortcutFields) {
	}

	private record SourceRoutes(List<RouteDescription> routes, UnreadableSource failure) {
	}

}
