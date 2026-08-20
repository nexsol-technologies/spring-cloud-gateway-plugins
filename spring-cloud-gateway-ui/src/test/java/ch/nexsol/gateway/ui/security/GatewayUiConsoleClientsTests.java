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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console can declare the providers of its login page itself, under the Spring
 * Security keys spelled out beneath its own prefix. They replace the registrations of the
 * application rather than adding to them: what the gateway registers is the plumbing its
 * routes relay tokens with, and none of it belongs on a page an operator reads.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.registration.staff.client-id=console-ui",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.registration.staff.client-secret=console-secret",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.registration.staff.client-name=Staff",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.registration.staff.authorization-grant-type=authorization_code",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.registration.staff.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.registration.staff.scope=openid,profile",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.provider.staff.authorization-uri=https://staff-idp.example.com/auth",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.provider.staff.token-uri=https://staff-idp.example.com/token",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.provider.staff.user-info-uri=https://staff-idp.example.com/userinfo",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.provider.staff.jwk-set-uri=https://staff-idp.example.com/jwks",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.provider.staff.user-name-attribute=preferred_username" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiConsoleClientsTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldOfferTheProvidersTheConsoleDeclaredForItself() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("/oauth2/authorization/staff").contains("Sign in with Staff"));
	}

	@Test
	void shouldLeaveOutTheRegistrationsOfTheApplication() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectBody(String.class)
			.value((body) -> assertThat(body).doesNotContain("/oauth2/authorization/relay")
				.doesNotContain("Sign in with Internal relay"));
	}

	@Test
	void shouldStartTheExchangeWithTheProviderOfTheConsole() {
		this.webTestClient.get()
			.uri("/oauth2/authorization/staff")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.value(HttpHeaders.LOCATION,
					(location) -> assertThat(location).startsWith("https://staff-idp.example.com/auth")
						.contains("client_id=console-ui"));
	}

	@Test
	void shouldNotStartAnExchangeWithTheProviderOfTheApplication() {
		this.webTestClient.get().uri("/oauth2/authorization/relay").exchange().expectStatus().isBadRequest();
	}

	/**
	 * A registration of the application a browser could sign in through, and which the
	 * console must still leave out: it is the one the gateway routes with, not the one
	 * its operators are meant to use.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class ClientRegistrationConfiguration {

		@Bean
		ReactiveClientRegistrationRepository clientRegistrationRepository() {
			return new InMemoryReactiveClientRegistrationRepository(ClientRegistration.withRegistrationId("relay")
				.clientName("Internal relay")
				.clientId("gateway")
				.clientSecret("secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.scope("openid")
				.authorizationUri("https://idp.example.com/auth")
				.tokenUri("https://idp.example.com/token")
				.userInfoUri("https://idp.example.com/userinfo")
				.userNameAttributeName("sub")
				.jwkSetUri("https://idp.example.com/jwks")
				.build());
		}

	}

}
