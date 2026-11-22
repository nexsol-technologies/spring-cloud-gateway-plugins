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

package ch.nexsol.gateway.database.controller;

import java.util.List;

import ch.nexsol.gateway.database.model.RouteResponseModel;
import ch.nexsol.gateway.database.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routes the management API has to be able to store and give back: a load-balanced
 * target, and a predicate argument holding a backslash.
 */
@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class RouteCreationRegressionTests {

	@Autowired
	RouteRepository routeRepository;

	@Autowired
	WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		this.routeRepository.deleteAll().block();
	}

	@Test
	void shouldCreateALoadBalancedRoute() {
		String body = """
				{
				"routeId": "load-balanced",
				"uri": "lb://service-a",
				"publicRoute": false,
				"predicates": [ { "name": "Method", "args": { "methods": "GET" } } ]
				}
				""";

		RouteResponseModel created = this.webTestClient.post()
			.uri("/api/gateway/routes")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(RouteResponseModel.class)
			.returnResult()
			.getResponseBody();

		assertThat(created.uri()).isEqualTo("lb://service-a");
	}

	@Test
	void shouldRejectARouteWhoseTargetHasNoScheme() {
		String body = """
				{
				"routeId": "no-scheme",
				"uri": "service-a/path",
				"predicates": [ { "name": "Method", "args": { "methods": "GET" } } ]
				}
				""";

		this.webTestClient.post()
			.uri("/api/gateway/routes")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()
			.expectStatus()
			.isBadRequest();
	}

	@Test
	void shouldGiveBackAPredicateArgumentHoldingABackslash() {
		String body = """
				{
				"routeId": "regexp-header",
				"uri": "http://service-a",
				"publicRoute": false,
				"predicates": [ { "name": "Header", "args": { "header": "X-Id", "regexp": "\\\\d+" } } ]
				}
				""";

		this.webTestClient.post()
			.uri("/api/gateway/routes")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()
			.expectStatus()
			.isOk();

		List<RouteResponseModel> routes = this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBodyList(RouteResponseModel.class)
			.hasSize(1)
			.returnResult()
			.getResponseBody();

		assertThat(routes.get(0).predicates()).hasSize(1);
		assertThat(routes.get(0).predicates().get(0).args()).containsEntry("regexp", "\\d+");
	}

	@Test
	void shouldCreateARouteThatLeavesTheOptionalFieldsOut() {
		String body = """
				{
				"routeId": "minimal",
				"uri": "http://service-a",
				"predicates": [ { "name": "Method", "args": { "methods": "GET" } } ]
				}
				""";

		RouteResponseModel created = this.webTestClient.post()
			.uri("/api/gateway/routes")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(RouteResponseModel.class)
			.returnResult()
			.getResponseBody();

		assertThat(created.publicRoute()).isFalse();
	}

}
