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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * A console behind its login page on a gateway publishing the OpenAPI hub: the shape a
 * real deployment takes, and the one the samples run without asserting anything about it.
 * <p>
 * Two things have to hold at once here. The documentation the hub declares to the console
 * is fetched by the Swagger UI as JSON, so a refusal has to read as a refusal rather than
 * as a redirect the script cannot follow. And the local user has to be a way in for a
 * caller with no browser, which over Basic is the only way it can present itself.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.user.name=superadmin",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=superadmin",
		"spring.cloud.gateway.server.webflux.ui.security.write-mode=permit-all" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiAuthenticatedHubTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldRefuseTheDocumentationFetchRatherThanRedirectIt() {
		this.webTestClient.get()
			.uri("/v3/api-docs/swagger-config")
			.accept(MediaType.APPLICATION_JSON)
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.doesNotExist(HttpHeaders.LOCATION);
	}

	@Test
	void shouldServeTheDocumentationToTheLocalUserOverBasic() {
		this.webTestClient.get()
			.uri("/v3/api-docs/swagger-config")
			.accept(MediaType.APPLICATION_JSON)
			.headers((headers) -> headers.setBasicAuth("superadmin", "superadmin"))
			.exchange()
			.expectStatus()
			.isOk();
	}

	@Test
	void shouldLeaveWhatTheHubProbesWithoutCredentialsOpen() {
		// The gateway reads its own contract over HTTP with nothing to present, so
		// closing it would remove the gateway from its own hub.
		this.webTestClient.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
	}

	@Test
	void shouldOpenTheShellToTheLocalUserOverBasic() {
		this.webTestClient.get()
			.uri("/ui")
			.headers((headers) -> headers.setBasicAuth("superadmin", "superadmin"))
			.exchange()
			.expectStatus()
			.isOk();
	}

	@Test
	void shouldKeepTheRouteManagementApiClosedDespiteTheWriteMode() {
		/*
		 * write-mode is read only when the console is open. Under mode=authenticated
		 * every path of the console asks for a principal, so permit-all here changes
		 * nothing and the API stays closed.
		 */
		this.webTestClient.get()
			.uri("/api/gateway/routes")
			.accept(MediaType.APPLICATION_JSON)
			.exchange()
			.expectStatus()
			.isUnauthorized();
	}

	/**
	 * What the OpenAPI hub and the route management declare to the console when they are
	 * on: the documentation a browser reads, the contract the hub itself polls, and the
	 * API that changes the routing table.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class HubConfiguration {

		@Bean
		SecuredPaths hubOpenapiSecuredPaths() {
			return SecuredPaths.governed("/v3/api-docs/swagger-config");
		}

		@Bean
		SecuredPaths hubProbedPaths() {
			return SecuredPaths.open("/v3/api-docs");
		}

		@Bean
		SecuredPaths routeApiSecuredPaths() {
			return SecuredPaths.api("/api/gateway/routes");
		}

	}

}
