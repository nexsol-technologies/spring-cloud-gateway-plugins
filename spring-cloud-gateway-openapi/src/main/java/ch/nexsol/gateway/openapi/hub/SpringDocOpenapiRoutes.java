/*
 * Copyright 2024 the original author or authors.
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

import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.properties.SwaggerUiConfigParameters;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.event.EventListener;

import static ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator.ROUTE_ID_PREFIX;

public class SpringDocOpenapiRoutes {

	private final RouteLocator routeLocator;

	private final SwaggerUiConfigParameters swaggerUiConfigParameters;

	public SpringDocOpenapiRoutes(RouteLocator routeLocator, SwaggerUiConfigParameters swaggerUiConfigParameters) {
		this.routeLocator = routeLocator;
		this.swaggerUiConfigParameters = swaggerUiConfigParameters;
	}

	@EventListener
	public void handleContextStart(RefreshRoutesEvent event) {

		this.swaggerUiConfigParameters.setUrls(new java.util.HashSet<>());
		this.swaggerUiConfigParameters.addGroup("-- Choose --");

		this.routeLocator.getRoutes().filter((route) -> route.getId().startsWith(ROUTE_ID_PREFIX)).doOnNext((route) -> {
			String name = (String) route.getMetadata().get("name");
			GroupedOpenApi.builder().pathsToMatch("/" + name + "/**").group(name).build();
			this.swaggerUiConfigParameters.addGroup(name);
		}).subscribe();

	}

}
