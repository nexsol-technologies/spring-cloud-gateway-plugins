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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ch.nexsol.gateway.commons.routes.RouteDefinitionSources;
import ch.nexsol.gateway.commons.routes.RouteOrigin;
import ch.nexsol.gateway.commons.routes.RouteTestReport;
import ch.nexsol.gateway.commons.routes.RouteTester;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.support.NameUtils;

/**
 * The tool that answers "which route would this request take": the described request is
 * held against the very predicates the gateway applies, route by route, by the route
 * tester the console uses.
 */
public class RouteMatchTools {

	private static final Duration SOURCE_TIMEOUT = Duration.ofSeconds(5);

	private final RouteTester tester;

	public RouteMatchTools(RouteTester tester) {
		this.tester = tester;
	}

	@McpTool(name = "match_request",
			description = "Which route a request would take: every route of the table is tried, each predicate "
					+ "reported as matched or not, and the first route whose predicates all match is the one the "
					+ "gateway would use. Use it to explain why a request reaches a service, or none.",
			annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
	public Mono<RouteTestReport> matchRequest(
			@McpToolParam(description = "The HTTP method; GET when omitted", required = false) String method,
			@McpToolParam(description = "The request path, with its query string if any") String path,
			@McpToolParam(description = "Request headers, one 'Name: value' per line; a Host header sets the host",
					required = false) String headers) {
		return this.tester.test(method, path, headers);
	}

	/**
	 * What is known of each route beyond its predicates, read from the route sources, for
	 * a gateway without the console: the console's tester reads its own inventory.
	 * @param locators the route definition sources
	 * @return the origins by route id
	 */
	public static Mono<Map<String, RouteOrigin>> origins(ObjectProvider<RouteDefinitionLocator> locators) {
		return Flux.fromIterable(RouteDefinitionSources.sources(locators))
			.flatMapSequential((locator) -> RouteDefinitionSources.read(locator, SOURCE_TIMEOUT)
				.onErrorReturn(List.of())
				.flatMapMany(Flux::fromIterable)
				.map((definition) -> Map.entry(definition.getId(), origin(definition, locator))))
			.collect(LinkedHashMap::new, (map, entry) -> map.putIfAbsent(entry.getKey(), entry.getValue()));
	}

	private static RouteOrigin origin(RouteDefinition definition, RouteDefinitionLocator locator) {
		return new RouteOrigin(RouteDefinitionSources.name(locator),
				definition.getFilters().stream().map(RouteMatchTools::describe).toList());
	}

	static String describe(FilterDefinition filter) {
		if (filter.getArgs().isEmpty()) {
			return filter.getName();
		}
		boolean positional = filter.getArgs()
			.keySet()
			.stream()
			.allMatch((key) -> key.startsWith(NameUtils.GENERATED_NAME_PREFIX));
		String rendered = filter.getArgs()
			.entrySet()
			.stream()
			.map((arg) -> positional ? arg.getValue() : arg.getKey() + "=" + arg.getValue())
			.collect(Collectors.joining(", "));
		return positional ? filter.getName() + "=" + rendered : filter.getName() + "(" + rendered + ")";
	}

}
