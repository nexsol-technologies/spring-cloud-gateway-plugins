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

package ch.nexsol.gateway.sample;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what a public route declared over every path opens &mdash; the frontend a
 * gateway typically serves last, behind all its other routes &mdash; and what it leaves
 * closed: the console and the route management API, which the gateway answers itself and
 * no route ever serves.
 */
@SpringBootTest(properties = { "eureka.client.enabled=false",
		"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false" })
@AutoConfigureWebTestClient
class PublicCatchAllRouteTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldServeThePublicFrontendWithoutCredentials() {
		this.webTestClient.get()
			.uri("/frontend/index.html")
			.exchange()
			.expectStatus()
			.value((status) -> assertThat(status).isNotIn(302, 401, 403));
	}

	@Test
	void shouldKeepTheConsoleBehindItsLoginPage() {
		this.webTestClient.get()
			.uri("/ui/routes/db")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?unauthorized");
	}

	@Test
	void shouldKeepTheRouteManagementApiBehindThatSameLogin() {
		this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?unauthorized");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class PublicFrontend {

		@Bean
		RouteLocator publicFrontend(RouteLocatorBuilder builder) {
			return builder.routes()
				.route("public_frontend",
						(route) -> route.order(Ordered.LOWEST_PRECEDENCE)
							.path("/**")
							.metadata("public", true)
							.uri("http://localhost:8080"))
				.build();
		}

	}

}
