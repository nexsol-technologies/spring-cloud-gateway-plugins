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

import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.model.RouteResponseModel;
import ch.nexsol.gateway.database.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class RouteViewControllerTests {

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
	void shouldRenderFullPage() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_HTML)
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("Routes management")
				.contains("Create a new route")
				.contains("fake"));
	}

	@Test
	void shouldRenderRouteListFragment() {
		this.webTestClient.get()
			.uri("/ui/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("id=\"route-list\"").contains("fake"));
	}

	@Test
	void shouldRenderPredicateRowWithAvailablePredicates() {
		this.webTestClient.get()
			.uri("/ui/predicate-row")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("element-row").contains("<select").contains("Method"));
	}

	@Test
	void shouldRenderElementArgsForSelectedPredicate() {
		this.webTestClient.get()
			.uri((builder) -> builder.path("/ui/element-args/predicate/0")
				.queryParam("predicates[0].name", "Method")
				.build())
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("predicates[0].args[methods]"));
	}

	@Test
	void shouldMarkOnlyStrictlyRequiredArgsAsRequired() {
		// Retry has only optional fields (no bean validation constraint): its inputs must
		// not be marked required, so the user can leave statuses, methods, etc. empty.
		this.webTestClient.get()
			.uri((builder) -> builder.path("/ui/element-args/filter/0").queryParam("filters[0].name", "Retry").build())
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("filters[0].args[retries]").doesNotContain("required"));

		// AddRequestHeader constrains name and value: its inputs keep the required
		// attribute.
		this.webTestClient.get()
			.uri((builder) -> builder.path("/ui/element-args/filter/1")
				.queryParam("filters[1].name", "AddRequestHeader")
				.build())
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("required"));
	}

	@Test
	void shouldCreateRouteFromForm() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("id", "");
		form.add("routeId", "ui-create");
		form.add("uri", "http://localhost:9999");
		form.add("order", "0");
		form.add("predicates[0].name", "Method");
		form.add("predicates[0].args[methods]", "GET");

		this.webTestClient.post()
			.uri("/ui/routes")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData(form))
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.exists("HX-Trigger")
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("ui-create").contains("hx-swap-oob"));

		this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBodyList(RouteResponseModel.class)
			.hasSize(2);
	}

	@Test
	void shouldReturnFormWithErrorWhenRouteIdAlreadyExists() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("id", "");
		form.add("routeId", "fake");
		form.add("uri", "http://localhost:9999");
		form.add("predicates[0].name", "Method");
		form.add("predicates[0].args[methods]", "GET");

		this.webTestClient.post()
			.uri("/ui/routes")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData(form))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("already exists").contains("alert-danger"));

		this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBodyList(RouteResponseModel.class)
			.hasSize(1);
	}

	@Test
	void shouldReturnFormWithErrorWhenNoPredicate() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("id", "");
		form.add("routeId", "no-predicate");
		form.add("uri", "http://localhost:9999");
		form.add("order", "0");

		this.webTestClient.post()
			.uri("/ui/routes")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData(form))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("at least one predicate").contains("alert-danger"));

		this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBodyList(RouteResponseModel.class)
			.hasSize(1);
	}

	@Test
	void shouldReturnFormWithErrorWhenPredicateArgumentMissing() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("id", "");
		form.add("routeId", "missing-arg");
		form.add("uri", "http://localhost:9999");
		form.add("order", "0");
		form.add("predicates[0].name", "Method");
		form.add("predicates[0].args[methods]", "");

		this.webTestClient.post()
			.uri("/ui/routes")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData(form))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("missing or invalid arguments").contains("alert-danger"));

		this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBodyList(RouteResponseModel.class)
			.hasSize(1);
	}

	@Test
	void shouldReturnFormWithErrorWhenPredicateArgumentTypeInvalid() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("id", "");
		form.add("routeId", "bad-type");
		form.add("uri", "http://localhost:9999");
		form.add("order", "0");
		form.add("predicates[0].name", "Path");
		form.add("predicates[0].args[patterns]", "/x/**");
		form.add("predicates[0].args[matchTrailingSlash]", "sadfsdf");

		this.webTestClient.post()
			.uri("/ui/routes")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData(form))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("missing or invalid arguments").contains("alert-danger"));

		this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBodyList(RouteResponseModel.class)
			.hasSize(1);
	}

	@Test
	void shouldDeleteRouteFromList() {
		Long id = this.routeRepository.findAll().blockFirst().getId();

		this.webTestClient.delete()
			.uri("/ui/routes/" + id)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("id=\"route-list\"").doesNotContain("fake"));

		this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBodyList(RouteResponseModel.class)
			.hasSize(0);
	}

}
