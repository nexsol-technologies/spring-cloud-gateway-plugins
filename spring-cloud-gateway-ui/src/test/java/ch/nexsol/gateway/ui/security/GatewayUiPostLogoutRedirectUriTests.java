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

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;

/**
 * The address the provider sends the browser back to after signing it out can be set
 * outright, for a gateway whose public address no header carries.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.post-logout-redirect-uri=https://gateway.example.com/ui/login?logout" })
@AutoConfigureWebTestClient(timeout = "300000")
@Import(GatewayUiOidcLogoutTests.ClientRegistrationConfiguration.class)
class GatewayUiPostLogoutRedirectUriTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldSendTheProviderPrincipalBackToTheConfiguredAddress() {
		this.webTestClient.mutateWith(csrf())
			.mutateWith(mockOidcLogin()
				.clientRegistration(GatewayUiOidcLogoutTests.ClientRegistrationConfiguration.REGISTRATION))
			.post()
			.uri("/ui/logout")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("https://idp.example.com/logout?id_token_hint=id-token"
					+ "&post_logout_redirect_uri=https://gateway.example.com/ui/login?logout");
	}

}
