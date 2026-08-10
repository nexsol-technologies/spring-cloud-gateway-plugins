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

package ch.nexsol.gateway.routes.openapi;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.util.StringUtils;

/**
 * Turns a parsed {@link OpenAPI} document into gateway {@link RouteDefinition}s according
 * to the {@link RouteGenerationMode} of the source.
 * <p>
 * Predicates and filters are built through the native {@code Name=arg0,arg1} shorthand
 * constructors so that list-valued factories such as {@code Path} and {@code Method}
 * receive the arguments in the form they expect.
 */
public class OpenApiRouteDefinitionMapper {

	/**
	 * Name of the filter a source asking to be validated is given, provided by the
	 * {@code spring-cloud-gateway-openapi-validation} plugin. Referred to by name so this
	 * module keeps no dependency towards it.
	 */
	private static final String VALIDATION_FILTER_NAME = "OpenapiValidation";

	/**
	 * Maps the document to route definitions.
	 * @param source the source configuration
	 * @param openApi the parsed OpenAPI document
	 * @return the generated route definitions
	 */
	public List<RouteDefinition> toRouteDefinitions(Source source, OpenAPI openApi) {
		Paths paths = openApi.getPaths();
		if (paths == null || paths.isEmpty()) {
			return List.of();
		}
		String basePath = resolveBasePath(source, openApi);
		String pathPrefix = normalizeBasePath(source.getPathPrefix());
		return switch (source.getMode()) {
			case PER_OPERATION -> perOperation(source, paths, basePath, pathPrefix);
			case AGGREGATED -> aggregated(source, paths, basePath, pathPrefix);
		};
	}

	private List<RouteDefinition> perOperation(Source source, Paths paths, String basePath, String pathPrefix) {
		List<RouteDefinition> routes = new ArrayList<>();
		for (Map.Entry<String, PathItem> pathEntry : paths.entrySet()) {
			String path = pathEntry.getKey();
			for (Map.Entry<HttpMethod, Operation> operationEntry : pathEntry.getValue()
				.readOperationsMap()
				.entrySet()) {
				HttpMethod method = operationEntry.getKey();
				RouteDefinition route = newRoute(source,
						operationRouteId(source, method, path, operationEntry.getValue()), basePath, pathPrefix);
				List<PredicateDefinition> predicates = new ArrayList<>();
				predicates.add(new PredicateDefinition("Path=" + pathPrefix + path));
				predicates.add(new PredicateDefinition("Method=" + method.name()));
				route.setPredicates(predicates);
				routes.add(route);
			}
		}
		return routes;
	}

	private List<RouteDefinition> aggregated(Source source, Paths paths, String basePath, String pathPrefix) {
		Set<String> methods = new LinkedHashSet<>();
		for (PathItem item : paths.values()) {
			item.readOperationsMap().keySet().forEach((method) -> methods.add(method.name()));
		}
		RouteDefinition route = newRoute(source, source.getId(), basePath, pathPrefix);
		List<PredicateDefinition> predicates = new ArrayList<>();
		String prefixed = paths.keySet().stream().map((path) -> pathPrefix + path).collect(Collectors.joining(","));
		predicates.add(new PredicateDefinition("Path=" + prefixed));
		if (!methods.isEmpty()) {
			predicates.add(new PredicateDefinition("Method=" + String.join(",", methods)));
		}
		route.setPredicates(predicates);
		return List.of(route);
	}

	private RouteDefinition newRoute(Source source, String id, String basePath, String pathPrefix) {
		RouteDefinition route = new RouteDefinition();
		route.setId(id);
		route.setUri(source.getUri());
		List<FilterDefinition> filters = new ArrayList<>();
		if (source.isValidate()) {
			// First in the chain, so a request that breaks the contract is denied before
			// any
			// other filter has done work for it — a retry or a rate limiter budget spent
			// on
			// a request that was never going to be forwarded.
			filters.add(validationFilter(source, pathPrefix));
		}
		// Filters apply in declaration order: drop the gateway-side prefix, then prepend
		// the base path the backend expects. RewritePath rather than StripPrefix, so the
		// route names the prefix it removes instead of removing whatever sits first.
		if (!pathPrefix.isEmpty()) {
			filters.add(new FilterDefinition("RewritePath=" + pathPrefix + "(?<remaining>/?.*), ${remaining}"));
		}
		// Operation paths are relative to the server base path; prepend it to reach the
		// real backend endpoint.
		if (!basePath.isEmpty()) {
			filters.add(new FilterDefinition("PrefixPath=" + basePath));
		}
		for (String filter : source.getFilters()) {
			filters.add(new FilterDefinition(filter));
		}
		route.setFilters(filters);
		route.setMetadata(new LinkedHashMap<>(source.getMetadata()));
		return route;
	}

	/**
	 * Builds the validation filter for a source that asked for it, reusing the contract
	 * and the prefix the source already declares so the two cannot drift apart.
	 * <p>
	 * Named by string and given explicit arguments rather than built from the
	 * {@code Name=arg0,arg1} shorthand the rest of this class uses. The string keeps this
	 * module free of any dependency towards the validation plugin, which only has to be
	 * on the classpath at runtime; the explicit arguments avoid the shorthand splitting a
	 * contract location that happens to contain a comma.
	 */
	private static FilterDefinition validationFilter(Source source, String pathPrefix) {
		FilterDefinition filter = new FilterDefinition();
		filter.setName(VALIDATION_FILTER_NAME);
		Map<String, String> args = new LinkedHashMap<>();
		args.put("specUrl", source.getSpecUrl());
		if (!pathPrefix.isEmpty()) {
			// The filter matches the gateway-side path, so it needs the prefix the routes
			// are exposed under, not the backend base path.
			args.put("pathPrefix", pathPrefix);
		}
		filter.setArgs(args);
		return filter;
	}

	/**
	 * Resolves the effective backend base path: the source override when configured
	 * (including an empty string to add no prefix), otherwise the path derived from the
	 * document's first server.
	 * @param source the source configuration
	 * @param openApi the parsed OpenAPI document
	 * @return the normalized base path, or an empty string
	 */
	private String resolveBasePath(Source source, OpenAPI openApi) {
		if (source.getBasePath() != null) {
			return normalizeBasePath(source.getBasePath());
		}
		return normalizeBasePath(serverRawPath(openApi));
	}

	/**
	 * Extracts the raw path component of the document's first server. Returns an empty
	 * string when there is no server or a templated server url that cannot be resolved
	 * statically.
	 * @param openApi the parsed OpenAPI document
	 * @return the raw server path, or an empty string
	 */
	private String serverRawPath(OpenAPI openApi) {
		List<Server> servers = openApi.getServers();
		if (servers == null || servers.isEmpty()) {
			return "";
		}
		String url = servers.get(0).getUrl();
		if (url == null || url.isBlank() || url.contains("{")) {
			return "";
		}
		if (url.startsWith("http://") || url.startsWith("https://")) {
			return URI.create(url).getPath();
		}
		if (url.startsWith("/")) {
			return url;
		}
		return "";
	}

	/**
	 * Normalizes a base path: blank or {@code "/"} become an empty string, a leading
	 * slash is ensured and a trailing slash is trimmed.
	 * @param path the raw base path
	 * @return the normalized base path, or an empty string
	 */
	private String normalizeBasePath(String path) {
		if (path == null || path.isBlank()) {
			return "";
		}
		String normalized = path.strip();
		if (normalized.equals("/")) {
			return "";
		}
		if (!normalized.startsWith("/")) {
			normalized = "/" + normalized;
		}
		return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
	}

	private String operationRouteId(Source source, HttpMethod method, String path, Operation operation) {
		if (StringUtils.hasText(operation.getOperationId())) {
			return source.getId() + "_" + operation.getOperationId();
		}
		String sanitizedPath = path.replaceAll("[^a-zA-Z0-9]+", "_").replaceAll("^_|_$", "");
		return source.getId() + "_" + method.name().toLowerCase() + "_" + sanitizedPath;
	}

}
