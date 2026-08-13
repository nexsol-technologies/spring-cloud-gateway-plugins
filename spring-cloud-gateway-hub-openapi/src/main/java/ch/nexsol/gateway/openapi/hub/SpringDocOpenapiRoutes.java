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

import java.util.Comparator;
import java.util.TreeSet;
import java.util.stream.Collectors;

import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl;
import org.springdoc.core.properties.SwaggerUiConfigProperties;

import org.springframework.cloud.gateway.event.RefreshRoutesResultEvent;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.event.EventListener;

import static ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator.ROUTE_ID_PREFIX;

/**
 * Keeps the SpringDoc Swagger UI configuration in sync with the discovered OpenAPI
 * routes, exposing one Swagger UI entry per aggregated service.
 */
public class SpringDocOpenapiRoutes {

	private static final Logger LOG = LoggerFactory.getLogger(SpringDocOpenapiRoutes.class);

	private final RouteLocator routeLocator;

	private final SwaggerUiConfigProperties swaggerUiConfigProperties;

	/**
	 * Creates a new instance.
	 * @param routeLocator the route locator providing the discovered OpenAPI routes
	 * @param swaggerUiConfigProperties the Swagger UI configuration to populate
	 */
	public SpringDocOpenapiRoutes(RouteLocator routeLocator, SwaggerUiConfigProperties swaggerUiConfigProperties) {
		this.routeLocator = routeLocator;
		this.swaggerUiConfigProperties = swaggerUiConfigProperties;
	}

	/**
	 * Rebuilds the Swagger UI URLs from the OpenAPI discovery routes once the gateway has
	 * refreshed its route table.
	 * <p>
	 * The result event is the one to listen to, not {@code RefreshRoutesEvent}: the
	 * caching route locator refreshes its cache asynchronously, so reading the routes
	 * when the refresh is only requested returns the previous table, and a service that
	 * has just been discovered would be missing from the list until the next refresh.
	 * @param event the result of the route refresh
	 */
	@EventListener(condition = "#event.success")
	public void handleRoutesRefreshed(RefreshRoutesResultEvent event) {
		this.routeLocator.getRoutes().filter((route) -> route.getId().startsWith(ROUTE_ID_PREFIX)).map((route) -> {
			String name = (String) route.getMetadata().get("name");
			return new SwaggerUrl(name, HubDiscoveryRouteLocator.API_DOCS_URL + "/" + name, null);
		})
			// A sorted set, because the console renders these as a dropdown: collecting
			// into a plain HashSet leaves the order to the hash of the names, so the list
			// an operator reads would be shuffled on every refresh.
			.collect(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(SwaggerUrl::getName))))
			.doOnNext(this.swaggerUiConfigProperties::setUrls)
			.subscribe(null, (ex) -> LOG.warn("Could not refresh the Swagger UI entries of the OpenAPI hub", ex));
	}

}
