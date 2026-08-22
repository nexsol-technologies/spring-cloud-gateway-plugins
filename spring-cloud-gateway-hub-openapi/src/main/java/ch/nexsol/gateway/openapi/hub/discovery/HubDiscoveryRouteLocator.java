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

package ch.nexsol.gateway.openapi.hub.discovery;

import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.openapi.HubOpenapiProperties;
import ch.nexsol.gateway.openapi.hub.HubOpenapiPaths;
import ch.nexsol.gateway.openapi.hub.OpenapiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.discovery.DiscoveryClientRouteDefinitionLocator;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.util.StringUtils;

/**
 * Route definition locator that, on top of the standard discovery routes, creates a
 * dedicated OpenAPI documentation route for every discovered service that exposes an
 * OpenAPI document.
 */
public class HubDiscoveryRouteLocator extends DiscoveryClientRouteDefinitionLocator {

	/**
	 * Prefix used to identify the generated OpenAPI documentation routes.
	 */
	public static final String ROUTE_ID_PREFIX = "openapi-docs-discovery-";

	private static final Logger LOG = LoggerFactory.getLogger(HubDiscoveryRouteLocator.class);

	/**
	 * Base path under which the aggregated OpenAPI documents are served.
	 */
	public static final String API_DOCS_URL = "/v3/api-docs";

	private final OpenapiService openapiService;

	private final int concurrency;

	// because it's private in DiscoveryClientRouteDefinitionLocator
	private final String routeIdPrefix;

	/**
	 * Creates a new locator probing with the default settings.
	 * @param discoveryClient the reactive discovery client
	 * @param properties the discovery locator properties
	 * @param openapiService the service used to discover the OpenAPI endpoints
	 */
	public HubDiscoveryRouteLocator(ReactiveDiscoveryClient discoveryClient, DiscoveryLocatorProperties properties,
			OpenapiService openapiService) {
		this(discoveryClient, properties, openapiService, new HubOpenapiProperties.Discovery());
	}

	/**
	 * Creates a new locator.
	 * @param discoveryClient the reactive discovery client
	 * @param properties the discovery locator properties
	 * @param openapiService the service used to discover the OpenAPI endpoints
	 * @param hubProperties the settings bounding the probes
	 */
	public HubDiscoveryRouteLocator(ReactiveDiscoveryClient discoveryClient, DiscoveryLocatorProperties properties,
			OpenapiService openapiService, HubOpenapiProperties.Discovery hubProperties) {
		super(discoveryClient, properties);
		this.openapiService = openapiService;
		this.concurrency = hubProperties.getConcurrency();

		if (StringUtils.hasText(properties.getRouteIdPrefix())) {
			this.routeIdPrefix = properties.getRouteIdPrefix();
		}
		else {
			this.routeIdPrefix = discoveryClient.getClass().getSimpleName() + "_";
		}
	}

	/**
	 * Returns an OpenAPI documentation route for each discovered service, derived from
	 * the standard discovery routes. Failures affecting a single service are logged and
	 * skipped so they do not prevent the discovery of the remaining routes.
	 * @return the OpenAPI documentation route definitions
	 */
	@Override
	public Flux<RouteDefinition> getRouteDefinitions() {
		return super.getRouteDefinitions()
			// do not process existing api doc routes
			.filter((route) -> !route.getId().startsWith(ROUTE_ID_PREFIX))
			// Bounded concurrency, where flatMap would otherwise default to 256: a route
			// refresh must cost the same whether the registry holds ten services or
			// several hundred.
			.flatMap((routeDefinition) -> {
				String routeId = routeDefinition.getId().replace(this.routeIdPrefix, "");
				// Isolate failures per route: a single unreachable/erroring service must
				// not abort the discovery of all the other OpenAPI routes.
				return this.openapiService.discoverOpenapiUrl(routeId, routeDefinition).onErrorResume((ex) -> {
					LOG.warn("Skipping OpenAPI discovery for route {} : {}", routeId, ex.getMessage());
					return Mono.empty();
				});
			}, this.concurrency)
			.map((openapiDiscover) -> {
				RouteDefinition routeDefinition = openapiDiscover.routeDefinition();

				String name = routeDefinition.getId().replace(this.routeIdPrefix, "");

				// Exposed path derived from the service id, not getUri().getHost().
				// getHost() is null for ids with URI-invalid chars such as '_', and
				// SpringDocOpenapiRoutes builds the Swagger UI URL from the same id,
				// so both must match to avoid a 404.
				String path = "/" + name;

				// Rewrite towards the path where the document was actually found (.json,
				// .yaml or a service-declared override), not a hard-coded /v3/api-docs.
				String discoveredPath = openapiDiscover.path();

				// create OPENAPI route
				RouteDefinition r = new RouteDefinition();
				r.setId(ROUTE_ID_PREFIX + name);
				r.setUri(routeDefinition.getUri());
				r.setMetadata(Map.of("name", name));
				r.setFilters(List.of(new FilterDefinition("RewritePath=" + API_DOCS_URL + path + ", " + discoveredPath),
						new FilterDefinition("OpenapiModifyResponseBody=" + path),
						new FilterDefinition(HubOpenapiPaths.REMOVE_COOKIE_FILTER)));
				r.setPredicates(List.of(new PredicateDefinition("Path=" + API_DOCS_URL + path)));

				LOG.debug("Create openapi route {} for existing discovery route {}", r.toString(),
						routeDefinition.toString());

				return r;
			});
	}

}
