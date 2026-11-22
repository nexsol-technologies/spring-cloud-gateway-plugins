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

package ch.nexsol.gateway.routes.security;

import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Tests delimiting what a public route opens: the requests the route serves, and none of
 * the endpoints the gateway answers itself.
 * <p>
 * A controller or a router function is dispatched ahead of the routes, so a public route
 * declared over {@code /**} never serves the request that reaches them, however well its
 * predicate matches. Opening them along with it would hand the endpoints of the gateway
 * to anybody.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, classes = PublicRouteLocalEndpointTests.Application.class)
class PublicRouteLocalEndpointTests {

	@LocalServerPort
	int port;

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void publicRouteIsServedWithoutCredentials() {
		client().get().uri("/shop/items").exchange().expectStatus().isEqualTo(HttpStatus.FOUND);
	}

	@Test
	void publicRouteDoesNotOpenAController() {
		client().get().uri("/local/admin").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void publicRouteDoesNotOpenARouterFunction() {
		client().get().uri("/local/function").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void publicRouteDoesNotOpenAControllerThatRefusesTheMethod() {
		client().post().uri("/local/admin").exchange().expectStatus().isUnauthorized();
	}

	/**
	 * A gateway that authenticates everything, serves two endpoints of its own, and
	 * declares one public route over every path.
	 */
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class Application {

		@Bean
		SecurityWebFilterChain applicationChain(ServerHttpSecurity http) {
			http.csrf(ServerHttpSecurity.CsrfSpec::disable);
			http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
			http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
			http.authorizeExchange((spec) -> spec.anyExchange().authenticated());
			return http.build();
		}

		@Bean
		RouteLocator publicCatchAll(RouteLocatorBuilder builder) {
			// Answered by the gateway itself, so the test needs no backend: what matters
			// is which chain let the request in.
			return builder.routes()
				.route("public_catch_all",
						(route) -> route.path("/**")
							.filters((filters) -> filters.redirect(HttpStatus.FOUND.value(), "https://example.org"))
							.metadata(PublicRouteMatcher.PUBLIC_METADATA_KEY, true)
							.uri("no://op"))
				.build();
		}

		@Bean
		RouterFunction<ServerResponse> localFunction() {
			return RouterFunctions.route()
				.GET("/local/function", (request) -> ServerResponse.ok().bodyValue("function"))
				.build();
		}

		@RestController
		static class Controller {

			@GetMapping("/local/admin")
			String admin() {
				return "admin";
			}

		}

	}

}
