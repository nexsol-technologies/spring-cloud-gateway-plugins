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

package ch.nexsol.gateway.openapi.hub;

import java.net.URI;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import ch.nexsol.gateway.routes.openapi.OpenapiSourcesLoader;
import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.util.StringUtils;

/**
 * Exposes the statically configured OpenAPI contracts of
 * {@code spring-cloud-gateway-routes-openapi} in the aggregated Swagger UI of the hub.
 * <p>
 * For each configured source it emits an OpenAPI documentation route carrying the hub
 * route id prefix, so that {@link SpringDocOpenapiRoutes} lists it in the Swagger UI
 * dropdown exactly like a discovered service. The route proxies the source
 * {@code specUrl} through the gateway and rewrites the OpenAPI {@code servers} section to
 * the gateway.
 */
public class StaticOpenapiDocsRouteLocator implements RouteDefinitionLocator {

	private final ObjectProvider<OpenapiSourcesLoader> sourcesLoader;

	/**
	 * Creates the locator.
	 * @param sourcesLoader the resolver of the OpenAPI sources, when the route generator
	 * is present
	 */
	public StaticOpenapiDocsRouteLocator(ObjectProvider<OpenapiSourcesLoader> sourcesLoader) {
		this.sourcesLoader = sourcesLoader;
	}

	@Override
	public Flux<RouteDefinition> getRouteDefinitions() {
		OpenapiSourcesLoader loader = this.sourcesLoader.getIfAvailable();
		if (loader == null) {
			return Flux.empty();
		}
		// Resolved sources, not the raw properties: those declared in a document reached
		// through 'sources-locations' belong in the aggregated Swagger UI too. Resolving
		// them may read a remote document, hence the elastic scheduler.
		return Mono.fromCallable(loader::load)
			.subscribeOn(Schedulers.boundedElastic())
			.flatMapMany(Flux::fromIterable)
			.filter((source) -> StringUtils.hasText(source.getId()) && StringUtils.hasText(source.getSpecUrl()))
			.map(this::toDocumentationRoute);
	}

	private RouteDefinition toDocumentationRoute(Source source) {
		String id = source.getId();
		URI specUri = URI.create(source.getSpecUrl());
		String base = specUri.getScheme() + "://" + specUri.getRawAuthority();
		String specPath = specUri.getRawPath();
		String docsPath = HubDiscoveryRouteLocator.API_DOCS_URL + "/" + id;

		RouteDefinition route = new RouteDefinition();
		route.setId(HubDiscoveryRouteLocator.ROUTE_ID_PREFIX + id);
		route.setUri(URI.create(base));
		route.setMetadata(Map.of("name", id));
		route.setPredicates(List.of(new PredicateDefinition("Path=" + docsPath)));
		// Proxy the contract through the gateway and advertise the gateway as the server
		// so
		// the "Try it out" calls target the gateway routes generated for the same
		// contract.
		route.setFilters(List.of(new FilterDefinition("RewritePath=" + docsPath + ", " + specPath),
				new FilterDefinition("OpenapiModifyResponseBody=/")));
		return route;
	}

}
