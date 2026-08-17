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

package ch.nexsol.gateway.ui.servicegraph;

import java.util.List;

import ch.nexsol.gateway.servicegraph.GraphEdge;
import ch.nexsol.gateway.servicegraph.ServiceGraphSnapshot;
import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class ServiceGraphControllerTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldRenderTheGraphPageWithinTheShell() {
		this.webTestClient.get()
			.uri("/ui/service-graph")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_HTML)
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("gw-sidebar")
				.contains("id=\"gg-chart\"")
				.contains("id=\"gg-tbody\"")
				.contains("id=\"gg-focus\"")
				.contains(">Service graph</span>"));
	}

	@Test
	void shouldReturnTheGraphAsJson() {
		this.webTestClient.get()
			.uri("/ui/service-graph/data")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.edges[0].from")
			.isEqualTo("frontend")
			.jsonPath("$.edges[0].to")
			.isEqualTo("service-a")
			.jsonPath("$.edges[0].calls")
			.isEqualTo(10)
			.jsonPath("$.nodes[0].kind")
			.isEqualTo("SERVICE");
	}

	@Test
	void shouldStateWhatTheGraphCovers() {
		this.webTestClient.get()
			.uri("/ui/service-graph/data")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.coverage")
			.isEqualTo("this instance only (test)");
	}

	@Test
	void shouldExposeTheCoveragePlaceholderOnThePage() {
		this.webTestClient.get()
			.uri("/ui/service-graph")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("id=\"gg-coverage\""));
	}

	@TestConfiguration
	static class ServiceGraphTestConfiguration {

		/**
		 * Answers with a graph of two hops, the second of which has the first one's
		 * target as its caller: the shape the view has to draw as a single service node.
		 */
		@Bean
		@Primary
		ServiceGraphSource testServiceGraphSource() {
			return () -> Mono.just(ServiceGraphSnapshot.of("this instance only (test)",
					List.of(new GraphEdge("frontend", "service-a", "a-route", 10, 1),
							new GraphEdge("service-a", "service-b", "b-route", 4, 0))));
		}

	}

}
