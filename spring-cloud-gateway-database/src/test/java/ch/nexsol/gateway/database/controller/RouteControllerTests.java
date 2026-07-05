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

import java.net.URI;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.model.FilterCreateModel;
import ch.nexsol.gateway.database.model.PredicateCreateModel;
import ch.nexsol.gateway.database.model.RouteCreateModel;
import ch.nexsol.gateway.database.model.RouteResponseModel;
import ch.nexsol.gateway.database.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class RouteControllerTests {

	@Autowired
	RouteRepository routeRepository;

	@Autowired
	WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		this.routeRepository.deleteAll().block();

		RouteEntity fake = new RouteEntity();
		fake.setRouteId("fake");
		fake.setUri("http://service-a");
		this.routeRepository.save(fake).block();
	}

	@Test
	void shouldRetrieveRoutes() {
		var result = this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBodyList(RouteResponseModel.class)
			.hasSize(1)
			.returnResult()
			.getResponseBody();

		assertThat(result.get(0).routeId()).isEqualTo("fake");
		assertThat(result.get(0).uri()).isEqualTo("http://service-a");
		System.out.println(result);
	}

	@Test
	void shouldRouteCreationWithBadPredicateFailedWith400() {
		RouteCreateModel model = new RouteCreateModel("test-create", URI.create("http://localhost-create"), null,
				List.of(new PredicateCreateModel("Method", Map.of("_unknown_", "??"))), null);
		var result = this.webTestClient.post()
			.uri("/api/gateway/routes")
			.body(BodyInserters.fromValue(model))
			.exchange()
			.expectStatus()
			.isBadRequest();
		System.out.println(result);
	}

	@Test
	void shouldRouteCreationWithoutPredicateFailedWith400() {
		RouteCreateModel model = new RouteCreateModel("test-create", URI.create("http://localhost-create"), null, null,
				null);
		this.webTestClient.post()
			.uri("/api/gateway/routes")
			.body(BodyInserters.fromValue(model))
			.exchange()
			.expectStatus()
			.isBadRequest();
	}

	@Test
	void shouldRouteCreationSuccessWithPredicate() {
		RouteCreateModel model = new RouteCreateModel("test-create-with-predicate",
				URI.create("http://localhost-create"), null,
				List.of(new PredicateCreateModel("Method", Map.of(MethodRoutePredicateFactory.METHODS_KEY, "GET"))),
				null);
		var result = this.webTestClient.post()
			.uri("/api/gateway/routes")
			.body(BodyInserters.fromValue(model))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(RouteResponseModel.class)
			.returnResult()
			.getResponseBody();

		System.out.println(result);

		assertThat(result.routeId()).isEqualTo("test-create-with-predicate");
		assertThat(result.uri()).isEqualTo("http://localhost-create");

		var result2 = this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBodyList(RouteResponseModel.class)
			.hasSize(2)
			.returnResult()
			.getResponseBody();

		assertThat(result2.get(0).routeId()).isEqualTo("fake");
		assertThat(result2.get(0).uri()).isEqualTo("http://service-a");

		assertThat(result2.get(1).routeId()).isEqualTo("test-create-with-predicate");
		assertThat(result2.get(1).uri()).isEqualTo("http://localhost-create");
		System.out.println(result2);

	}

	@Test
	void shouldRouteCreationSuccessWithPredicateAndFilter() {
		RouteCreateModel model = new RouteCreateModel("test-create-with-predicate-and-filter",
				URI.create("http://localhost-create"), null,
				List.of(new PredicateCreateModel("Method", Map.of(MethodRoutePredicateFactory.METHODS_KEY, "GET"))),
				List.of(new FilterCreateModel("AddRequestHeader", Map.of(GatewayFilterFactory.NAME_KEY, "X-Request-red",
						GatewayFilterFactory.VALUE_KEY, "blue"))));
		var result = this.webTestClient.post()
			.uri("/api/gateway/routes")
			.body(BodyInserters.fromValue(model))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(RouteResponseModel.class)
			.returnResult()
			.getResponseBody();

		System.out.println(result);

		assertThat(result.routeId()).isEqualTo("test-create-with-predicate-and-filter");
		assertThat(result.uri()).isEqualTo("http://localhost-create");

		var result2 = this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBodyList(RouteResponseModel.class)
			.hasSize(2)
			.returnResult()
			.getResponseBody();

		assertThat(result2.get(0).routeId()).isEqualTo("fake");
		assertThat(result2.get(0).uri()).isEqualTo("http://service-a");

		assertThat(result2.get(1).routeId()).isEqualTo("test-create-with-predicate-and-filter");
		assertThat(result2.get(1).uri()).isEqualTo("http://localhost-create");
		assertThat(result2.get(1).predicates()).allMatch((p) -> "Method".equals(p.name()));
		assertThat(result2.get(1).filters()).allMatch((p) -> "AddRequestHeader".equals(p.name()));
		System.out.println(result2);

	}

	@Test
	void shouldRouteUpdateSuccessWithPredicateAndFilter() {
		RouteCreateModel model = new RouteCreateModel("test-create-and-update-with-predicate-and-filter",
				URI.create("http://localhost-create"), null,
				List.of(new PredicateCreateModel("Method", Map.of(MethodRoutePredicateFactory.METHODS_KEY, "GET"))),
				null);

		var resultCreate = this.webTestClient.post()
			.uri("/api/gateway/routes")
			.body(BodyInserters.fromValue(model))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(RouteResponseModel.class)
			.returnResult()
			.getResponseBody();
		assertThat(resultCreate.routeId()).isEqualTo("test-create-and-update-with-predicate-and-filter");
		assertThat(resultCreate.uri()).isEqualTo("http://localhost-create");

		model = new RouteCreateModel("test-update-with-predicate-and-filter", URI.create("http://localhost-create"),
				null,
				List.of(new PredicateCreateModel("Method", Map.of(MethodRoutePredicateFactory.METHODS_KEY, "GET"))),
				List.of(new FilterCreateModel("AddRequestHeader", Map.of(GatewayFilterFactory.NAME_KEY, "X-Request-red",
						GatewayFilterFactory.VALUE_KEY, "blue"))));

		var resultUpdate = this.webTestClient.put()
			.uri("/api/gateway/routes/" + resultCreate.id())
			.body(BodyInserters.fromValue(model))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(RouteResponseModel.class)
			.returnResult()
			.getResponseBody();

		assertThat(resultUpdate.routeId()).isEqualTo("test-update-with-predicate-and-filter");
		assertThat(resultUpdate.uri()).isEqualTo("http://localhost-create");

		var resultGet = this.webTestClient.get()
			.uri("/api/gateway/routes/" + resultCreate.id())
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(RouteResponseModel.class)
			.returnResult()
			.getResponseBody();

		assertThat(resultGet.routeId()).isEqualTo("test-update-with-predicate-and-filter");
		assertThat(resultGet.uri()).isEqualTo("http://localhost-create");
		assertThat(resultGet.predicates()).allMatch((p) -> "Method".equals(p.name()));
		assertThat(resultGet.filters()).allMatch((p) -> "AddRequestHeader".equals(p.name()));
		System.out.println(resultGet);

	}

}
