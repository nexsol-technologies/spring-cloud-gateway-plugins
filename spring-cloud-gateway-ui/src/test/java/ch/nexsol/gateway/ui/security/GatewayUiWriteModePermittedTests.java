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
 * Tests the way back for a deployment that drives the route management with a script and
 * has no credentials to give it: the endpoints that change the gateway follow the console
 * like any other path.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret",
		"spring.cloud.gateway.server.webflux.ui.security.write-mode=permit-all" })
@AutoConfigureWebTestClient
class GatewayUiWriteModePermittedTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldFollowTheConsoleRatherThanAskingForAPrincipal() {
		this.webTestClient.get().uri("/plugin/routes").exchange().expectStatus().isNotFound();
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
