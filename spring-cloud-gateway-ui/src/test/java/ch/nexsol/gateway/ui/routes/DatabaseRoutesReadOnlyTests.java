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

import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the view when the plugin holding the routes publishes them for reading alone: the
 * page renders, and nothing on it or behind it changes a route.
 */
@SpringBootTest(properties = "spring.cloud.gateway.server.webflux.routes-database.access=read-only")
@AutoConfigureWebTestClient(timeout = "300000")
class DatabaseRoutesReadOnlyTests {

	@Autowired
	RouteRepository routeRepository;

	@Autowired
	WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		this.routeRepository.deleteAll().block();
		RouteEntity route = new RouteEntity();
		route.setRouteId("read-only-route");
		route.setUri("http://service-a");
		this.routeRepository.save(route).block();
	}

	@Test
	void shouldRenderThePageWithoutTheButtonsLeadingToAChange() {
		this.webTestClient.get()
			.uri("/ui/routes/db")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("read-only-route")
				// A page offering an action that answers 405 would be worse than a page
				// that does not offer it.
				.doesNotContain("hx-delete")
				.doesNotContain("Create route"));
	}

	@Test
	void shouldRefuseAChangeBuiltByHand() {
		this.webTestClient.post().uri("/ui/routes/db").exchange().expectStatus().isEqualTo(405);
		this.webTestClient.delete().uri("/ui/routes/db/1").exchange().expectStatus().isEqualTo(405);
	}

	@Test
	void shouldLeaveTheStoredRoutesUntouched() {
		assertThat(this.routeRepository.count().block()).isEqualTo(1);
	}

}
