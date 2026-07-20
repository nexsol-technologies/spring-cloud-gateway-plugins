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

import java.util.stream.Collectors;

import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl;
import org.springdoc.core.properties.SwaggerUiConfigProperties;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.event.EventListener;

import static ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator.ROUTE_ID_PREFIX;

/**
 * Keeps the SpringDoc Swagger UI configuration in sync with the discovered OpenAPI
 * routes, exposing one Swagger UI entry per aggregated service.
 */
public class SpringDocOpenapiRoutes {

	private final RouteLocator routeLocator;

	private SwaggerUiConfigProperties swaggerUiConfigProperties;

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
	 * Rebuilds the Swagger UI URLs from the current OpenAPI discovery routes whenever the
	 * gateway routes are refreshed.
	 * @param event the route refresh event
	 */
	@EventListener
	public void handleContextStart(RefreshRoutesEvent event) {
		this.routeLocator.getRoutes().filter((route) -> route.getId().startsWith(ROUTE_ID_PREFIX)).map((route) -> {
			String name = (String) route.getMetadata().get("name");
			return new SwaggerUrl(name, HubDiscoveryRouteLocator.API_DOCS_URL + "/" + name, null);
		}).collect(Collectors.toSet()).doOnNext(this.swaggerUiConfigProperties::setUrls).subscribe();
	}

}
