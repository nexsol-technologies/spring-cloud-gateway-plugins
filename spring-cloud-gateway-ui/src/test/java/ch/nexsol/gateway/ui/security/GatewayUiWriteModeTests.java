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

package ch.nexsol.gateway.ui.security;

import ch.nexsol.gateway.commons.security.SecuredPaths;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Tests an <em>open</em> console: the views are permitted, and the endpoints a plugin
 * declared as changing the gateway are not.
 * <p>
 * A permitted path answers {@code 404} here rather than {@code 200}: nothing serves the
 * paths this test contributes, and what is being read is whether security answered before
 * routing did.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.user.name=operator",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret" })
@AutoConfigureWebTestClient
class GatewayUiWriteModeTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldServeTheConsoleWithoutCredentials() {
		this.webTestClient.get().uri("/ui").exchange().expectStatus().isOk();
	}

	@Test
	void shouldLeaveTheContributionsThatOnlyReadOpen() {
		this.webTestClient.get().uri("/plugin/browsed").exchange().expectStatus().isNotFound();
		this.webTestClient.get().uri("/plugin/polled").exchange().expectStatus().isNotFound();
	}

	@Test
	void shouldCloseTheEndpointsThatChangeTheGateway() {
		this.webTestClient.get().uri("/plugin/routes").exchange().expectStatus().isUnauthorized();
		this.webTestClient.get().uri("/plugin/routes-page").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void shouldOpenThemToTheCredentialsTheConsoleHolds() {
		this.webTestClient.get()
			.uri("/plugin/routes")
			.headers((headers) -> headers.setBasicAuth("operator", "console-secret"))
			.exchange()
			.expectStatus()
			.isNotFound();
	}

	@Test
	void shouldRejectCredentialsThatAreNotTheOnesTheConsoleHolds() {
		this.webTestClient.get()
			.uri("/plugin/routes")
			.headers((headers) -> headers.setBasicAuth("operator", "wrong"))
			.exchange()
			.expectStatus()
			.isUnauthorized();
	}

	/**
	 * A plugin declaring one path of each kind, as the OpenAPI hub, the discovery metrics
	 * and the route management do.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class ContributingPluginConfiguration {

		@Bean
		SecuredPaths browsedPaths() {
			return SecuredPaths.governed("/plugin/browsed");
		}

		@Bean
		SecuredPaths polledPaths() {
			return SecuredPaths.open("/plugin/polled");
		}

		@Bean
		SecuredPaths routeManagementPaths() {
			return SecuredPaths.api("/plugin/routes");
		}

		@Bean
		SecuredPaths routeManagementViewPaths() {
			return SecuredPaths.write("/plugin/routes-page");
		}

	}

}
