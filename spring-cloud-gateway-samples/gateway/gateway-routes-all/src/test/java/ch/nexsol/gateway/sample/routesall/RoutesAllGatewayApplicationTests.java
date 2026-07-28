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

package ch.nexsol.gateway.sample.routesall;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the two things this combination is about: several sources aggregated into one
 * route locator, and a route declaring itself public escaping the security chain.
 * <p>
 * The OpenAPI source is turned off — it reads a contract over the network, which a test
 * has no business depending on.
 */
@SpringBootTest(properties = "spring.cloud.gateway.server.webflux.routes-openapi.enabled=false")
@AutoConfigureWebTestClient
class RoutesAllGatewayApplicationTests {

	@Autowired
	RouteDefinitionLocator routeDefinitionLocator;

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldAggregateEverySourceIntoOneRouteLocator() {
		StepVerifier.create(this.routeDefinitionLocator.getRouteDefinitions().map(RouteDefinition::getId).collectList())
			.assertNext((ids) -> assertThat(ids).contains("properties_route", "files_private", "files_public"))
			.verifyComplete();
	}

	@Test
	void shouldAuthenticateARouteThatIsNotFlaggedPublic() {
		this.webTestClient.get().uri("/files-private/anything").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void shouldLetAPublicRouteThroughWithoutCredentials() {
		this.webTestClient.get()
			.uri("/files-public/sample")
			.exchange()
			.expectStatus()
			.value((status) -> assertThat(status).isNotEqualTo(401));
	}

}
