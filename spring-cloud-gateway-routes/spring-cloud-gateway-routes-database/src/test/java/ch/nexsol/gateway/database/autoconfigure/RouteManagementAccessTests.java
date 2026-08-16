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

import ch.nexsol.gateway.database.controller.RouteController;
import ch.nexsol.gateway.database.locator.DatabaseRouteDefinitionLocator;
import ch.nexsol.gateway.dbwiring.AutoConfiguredApplication;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@code access=none}: the plugin publishes no endpoint at all, and the database
 * keeps feeding the gateway its routes just the same.
 */
@SpringBootTest(classes = AutoConfiguredApplication.class,
		properties = "spring.cloud.gateway.server.webflux.routes-database.access=none")
@AutoConfigureWebTestClient(timeout = "300000")
class RouteManagementAccessTests {

	@Autowired
	ApplicationContext context;

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldRegisterNoController() {
		assertThat(this.context.getBeansOfType(RouteController.class)).isEmpty();
	}

	@Test
	void shouldDeclareNoPathToWhoeverGovernsThem() {
		assertThat(this.context.containsBean("routeApiSecuredPaths")).isFalse();
	}

	@Test
	void shouldKeepFeedingTheGatewayItsRoutes() {
		// The whole point of the setting: what is published moves, where the routes come
		// from does not.
		assertThat(this.context.getBeansOfType(DatabaseRouteDefinitionLocator.class)).hasSize(1);
	}

	@Test
	void shouldServeNoApi() {
		this.webTestClient.get().uri("/api/gateway/routes").exchange().expectStatus().isNotFound();
	}

}
