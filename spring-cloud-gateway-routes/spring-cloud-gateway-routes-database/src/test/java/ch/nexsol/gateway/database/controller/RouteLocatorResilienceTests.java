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
import ch.nexsol.gateway.database.repository.RouteRepository;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class RouteLocatorResilienceTests {

	@Autowired
	RouteRepository routeRepository;

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	WebTestClient webTestClient;

	@Test
	void aRouteWithoutPredicateMustNotBreakStaticResources() {
		this.routeRepository.deleteAll().block();

		// A gateway route with no predicate acts as a catch-all: without the locator
		// guard
		// it would intercept every request (static assets included) and proxy it,
		// breaking
		// the whole gateway. Such a row can only exist as legacy data now.
		RouteEntity bad = new RouteEntity();
		bad.setRouteId("no-predicate-route");
		bad.setUri("http://service-a");
		this.routeRepository.save(bad).block();
		this.publisher.publishEvent(new RefreshRoutesEvent(this));

		this.webTestClient.get().uri("/css/bootstrap.min.css").exchange().expectStatus().isOk();
		this.webTestClient.get().uri("/js/htmx.min.js").exchange().expectStatus().isOk();
		this.webTestClient.get().uri("/ui/routes/db").exchange().expectStatus().isOk();
	}

}
