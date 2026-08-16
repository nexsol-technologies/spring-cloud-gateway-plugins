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

package ch.nexsol.gateway.database.autoconfigure;

import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.repository.RouteRepository;
import ch.nexsol.gateway.dbwiring.AutoConfiguredApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@code access=read-only}: the routes can be read, and nothing that would change
 * them is served &mdash; whoever is asking.
 */
@SpringBootTest(classes = AutoConfiguredApplication.class,
		properties = "spring.cloud.gateway.server.webflux.routes-database.access=read-only")
@AutoConfigureWebTestClient(timeout = "300000")
class RouteManagementReadOnlyTests {

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
	void shouldStillServeTheRoutes() {
		this.webTestClient.get().uri("/api/gateway/routes").exchange().expectStatus().isOk();
	}

	@Test
	void shouldRefuseWhatWouldChangeThem() {
		this.webTestClient.post()
			.uri("/api/gateway/routes")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"routeId\":\"new\",\"uri\":\"http://service-b\"}")
			.exchange()
			.expectStatus()
			.isEqualTo(405);
		this.webTestClient.delete().uri("/api/gateway/routes/1").exchange().expectStatus().isEqualTo(405);
		this.webTestClient.put()
			.uri("/api/gateway/routes/1")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"routeId\":\"changed\",\"uri\":\"http://service-b\"}")
			.exchange()
			.expectStatus()
			.isEqualTo(405);
	}

	@Test
	void shouldLeaveTheStoredRoutesUntouched() {
		assertThat(this.routeRepository.count().block()).isEqualTo(1);
	}

}
