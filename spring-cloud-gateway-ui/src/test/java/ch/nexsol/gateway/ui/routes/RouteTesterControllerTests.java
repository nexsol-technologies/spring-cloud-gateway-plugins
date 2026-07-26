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

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class RouteTesterControllerTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldRenderTesterPageWithinTheShell() {
		this.webTestClient.get()
			.uri("/ui/routes/test")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_HTML)
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("gw-sidebar")
				.contains("id=\"gt-path\"")
				.contains("id=\"gt-result\"")
				.contains(">Route tester</span>"));
	}

	@Test
	void shouldReportTheRouteThatWouldHandleTheRequest() {
		test("method", "GET", "path", "/alpha/orders").value((body) -> assertThat(body).contains("handled by route")
			.contains("alpha")
			.contains("http://localhost/alpha/orders")
			.contains(">match<"));
	}

	@Test
	void shouldReportNoMatchAndWhyForAPathNoRouteAccepts() {
		test("method", "GET", "path", "/nowhere").value(
				(body) -> assertThat(body).contains("no route matches").contains(">no match<").contains("/alpha/**"));
	}

	@Test
	void shouldApplyTheHostHeaderOfTheDescribedRequest() {
		test("method", "GET", "path", "/alpha/orders", "headers", "Host: api.example.com")
			.value((body) -> assertThat(body).contains("http://api.example.com/alpha/orders"));
	}

	@Test
	void shouldReportTheFailureForARequestThatCannotBeBuilt() {
		// A broken percent-escape is the one thing the URI builder refuses to fix up.
		test("method", "GET", "path", "/orders%zz")
			.value((body) -> assertThat(body).contains("The request could not be built"));
	}

	private WebTestClient.BodySpec<String, ?> test(String... formPairs) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (int index = 0; index < formPairs.length; index += 2) {
			form.add(formPairs[index], formPairs[index + 1]);
		}
		return this.webTestClient.post()
			.uri("/ui/routes/test")
			.body(BodyInserters.fromFormData(form))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class);
	}

	/** Contributes the route the tester is expected to find. */
	@TestConfiguration
	static class TesterTestConfiguration {

		@Bean
		SampleRouteDefinitionLocator testerRouteDefinitionLocator() {
			return new SampleRouteDefinitionLocator();
		}

	}

	static class SampleRouteDefinitionLocator implements RouteDefinitionLocator {

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			PredicateDefinition path = new PredicateDefinition();
			path.setName("Path");
			path.setArgs(Map.of("_genkey_0", "/alpha/**"));

			RouteDefinition definition = new RouteDefinition();
			definition.setId("alpha");
			definition.setUri(URI.create("http://alpha.example.com"));
			definition.setPredicates(List.of(path));
			return Flux.just(definition);
		}

	}

}
