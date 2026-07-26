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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import reactor.core.publisher.Flux;

import org.springframework.cloud.gateway.event.RefreshRoutesResultEvent;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SpringDocOpenapiRoutes}, which mirrors the discovered documentation
 * routes into the Swagger UI configuration.
 */
class SpringDocOpenapiRoutesTests {

	private final SwaggerUiConfigProperties properties = new SwaggerUiConfigProperties();

	@Test
	void publishesOneEntryPerDiscoveredDocumentationRoute() {
		AtomicReference<List<String>> services = new AtomicReference<>(List.of("service-a"));
		SpringDocOpenapiRoutes routes = new SpringDocOpenapiRoutes(locator(services), this.properties);

		routes.handleRoutesRefreshed(new RefreshRoutesResultEvent(this));

		assertThat(this.properties.getUrls()).extracting(SwaggerUrl::getName).containsExactly("service-a");
		assertThat(this.properties.getUrls()).extracting(SwaggerUrl::getUrl)
			.containsExactly(HubDiscoveryRouteLocator.API_DOCS_URL + "/service-a");
	}

	@Test
	void picksUpAServiceDiscoveredSinceTheLastRefresh() {
		AtomicReference<List<String>> services = new AtomicReference<>(List.of("service-a"));
		SpringDocOpenapiRoutes routes = new SpringDocOpenapiRoutes(locator(services), this.properties);
		routes.handleRoutesRefreshed(new RefreshRoutesResultEvent(this));

		services.set(List.of("service-a", "service-b"));
		routes.handleRoutesRefreshed(new RefreshRoutesResultEvent(this));

		assertThat(this.properties.getUrls()).extracting(SwaggerUrl::getName)
			.containsExactlyInAnyOrder("service-a", "service-b");
	}

	@Test
	void ignoresTheRoutesThatAreNotDocumentationOnes() {
		RouteLocator locator = () -> Flux.just(route("some-business-route", "orders"));
		SpringDocOpenapiRoutes routes = new SpringDocOpenapiRoutes(locator, this.properties);

		routes.handleRoutesRefreshed(new RefreshRoutesResultEvent(this));

		assertThat(this.properties.getUrls()).isEmpty();
	}

	private static RouteLocator locator(AtomicReference<List<String>> services) {
		return () -> Flux.fromIterable(services.get())
			.map((name) -> route(HubDiscoveryRouteLocator.ROUTE_ID_PREFIX + name, name));
	}

	private static Route route(String id, String name) {
		return Route.async()
			.id(id)
			.uri("http://" + name)
			.predicate((exchange) -> true)
			.metadata(Map.of("name", name))
			.build();
	}

}
