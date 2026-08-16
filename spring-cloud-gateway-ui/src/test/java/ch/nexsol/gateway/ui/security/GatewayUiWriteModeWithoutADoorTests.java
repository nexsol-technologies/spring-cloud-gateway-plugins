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
 * Tests an open console with nothing to authenticate against: no local user, no user
 * directory, no issuer.
 * <p>
 * The endpoints that change the gateway stay reachable, and a warning names them at
 * start-up. Closing a door that has no key behind it would lock an application out of its
 * own route management, and turn an upgrade of the plugin into an outage.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
		+ "org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration")
@AutoConfigureWebTestClient
class GatewayUiWriteModeWithoutADoorTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldLeaveTheEndpointsThatChangeTheGatewayReachable() {
		this.webTestClient.get().uri("/plugin/routes").exchange().expectStatus().isNotFound();
	}

	@Test
	void shouldStillServeTheConsole() {
		this.webTestClient.get().uri("/ui").exchange().expectStatus().isOk();
	}

	/**
	 * A plugin declaring the endpoints that change the gateway.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class ContributingPluginConfiguration {

		@Bean
		SecuredPaths routeManagementPaths() {
			return SecuredPaths.api("/plugin/routes");
		}

	}

}
