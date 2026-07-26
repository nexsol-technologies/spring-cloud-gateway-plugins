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

package ch.nexsol.gateway.ui.routes;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class RouteInventoryControllerTests {

	@Autowired
	WebTestClient webTestClient;

	@Autowired
	RefreshRoutesEventCounter refreshCounter;

	@Test
	void shouldRenderRoutesPageWithinTheShell() {
		this.webTestClient.get()
			.uri("/ui/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_HTML)
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("gw-sidebar")
				.contains("id=\"gr-inventory\"")
				.contains("id=\"gr-filter\"")
				.contains(">Routes</span>"));
	}

	@Test
	void shouldListEachRouteWithItsSourcePredicatesAndFilters() {
		this.webTestClient.get()
			.uri("/ui/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("alpha")
				.contains("http://alpha.example.com")
				.contains("Sample")
				.contains("Path=/alpha/**")
				.contains("StripPrefix=1"));
	}

	@Test
	void shouldFlagARouteIdDeclaredByMoreThanOneSource() {
		this.webTestClient.get()
			.uri("/ui/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("duplicate id"));
	}

	@Test
	void shouldRenderOnlyTheTableFragmentOnRefresh() {
		this.webTestClient.get()
			.uri("/ui/routes/list")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("id=\"gr-inventory\"").doesNotContain("gw-sidebar"));
	}

	@Test
	void shouldLeaveTheDatabaseRoutesPageMappedUnderItsOwnSubPath() {
		// This view owns /ui/routes; the routes-database plugin owns /ui/routes/db.
		this.webTestClient.get()
			.uri("/ui/routes/db")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.isEqualTo("database routes page");
	}

	@Test
	void shouldAskTheGatewayToRebuildItsRouteTableOnReload() {
		int before = this.refreshCounter.count();

		this.webTestClient.post()
			.uri("/ui/routes/reload")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("id=\"gr-inventory\""));

		assertThat(this.refreshCounter.count()).isGreaterThan(before);
	}

	/**
	 * Stands in for a plugin contributing routes from its own source. The source name
	 * shown in the UI is derived from the locator class name, hence {@code Sample}.
	 */
	@TestConfiguration
	static class RoutesTestConfiguration {

		@Bean
		SampleRouteDefinitionLocator sampleRouteDefinitionLocator() {
			return new SampleRouteDefinitionLocator();
		}

		@Bean
		ShadowingRouteDefinitionLocator shadowingRouteDefinitionLocator() {
			return new ShadowingRouteDefinitionLocator();
		}

		@Bean
		RefreshRoutesEventCounter refreshRoutesEventCounter() {
			return new RefreshRoutesEventCounter();
		}

		@Bean
		DatabaseRoutesStubController databaseRoutesStubController() {
			return new DatabaseRoutesStubController();
		}

	}

	/**
	 * Stands in for the management page of {@code spring-cloud-gateway-routes-database},
	 * which is mapped under a sub-path of this view.
	 */
	@Controller
	@RequestMapping("/ui/routes/db")
	static class DatabaseRoutesStubController {

		@GetMapping
		@ResponseBody
		String index() {
			return "database routes page";
		}

	}

	static class RefreshRoutesEventCounter {

		private final AtomicInteger refreshes = new AtomicInteger();

		@EventListener
		void onRefresh(RefreshRoutesEvent event) {
			this.refreshes.incrementAndGet();
		}

		int count() {
			return this.refreshes.get();
		}

	}

	static class SampleRouteDefinitionLocator implements RouteDefinitionLocator {

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			PredicateDefinition path = new PredicateDefinition();
			path.setName("Path");
			path.setArgs(Map.of("_genkey_0", "/alpha/**"));
			FilterDefinition stripPrefix = new FilterDefinition();
			stripPrefix.setName("StripPrefix");
			stripPrefix.setArgs(Map.of("_genkey_0", "1"));

			RouteDefinition definition = new RouteDefinition();
			definition.setId("alpha");
			definition.setUri(URI.create("http://alpha.example.com"));
			definition.setPredicates(List.of(path));
			definition.setFilters(List.of(stripPrefix));
			return Flux.just(definition);
		}

	}

	/**
	 * Declares the same route id as the sample source, so the duplicate badge shows up.
	 */
	static class ShadowingRouteDefinitionLocator implements RouteDefinitionLocator {

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			RouteDefinition definition = new RouteDefinition();
			definition.setId("alpha");
			definition.setUri(URI.create("http://alpha.other.example.com"));
			return Flux.just(definition);
		}

	}

}
