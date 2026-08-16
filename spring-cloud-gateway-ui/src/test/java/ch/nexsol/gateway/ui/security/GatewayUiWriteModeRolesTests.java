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
 * Tests that the roles required by the console are asked for on the endpoints that change
 * the gateway, even while the console itself is open: they are the one place where a
 * principal is checked at all, so letting any authenticated caller through would make
 * {@code required-roles} mean nothing where it matters most.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.user.name=operator",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret",
		"spring.cloud.gateway.server.webflux.ui.security.user.roles=READ",
		"spring.cloud.gateway.server.webflux.ui.security.required-roles=ADMIN" })
@AutoConfigureWebTestClient
class GatewayUiWriteModeRolesTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldRefuseAPrincipalWithoutTheRequiredRole() {
		this.webTestClient.get()
			.uri("/plugin/routes")
			.headers((headers) -> headers.setBasicAuth("operator", "console-secret"))
			.exchange()
			.expectStatus()
			.isForbidden();
	}

	@Test
	void shouldStillServeTheOpenConsoleToAnybody() {
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
