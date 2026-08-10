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
 * Tests the console signed into through an identity provider: the login page offers the
 * registered providers, the authorization exchange starts, and the credentials form is
 * left out since there is no local user for it to authenticate against.
 */
@SpringBootTest(properties = "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated")
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiOidcLoginTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldOfferAButtonForEachRegisteredProvider() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("/oauth2/authorization/keycloak").contains("Sign in with Acme"));
	}

	@Test
	void shouldLeaveOutTheCredentialsFormWhenThereIsNoLocalUser() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectBody(String.class)
			.value((body) -> assertThat(body).doesNotContain("name=\"username\""));
	}

	@Test
	void shouldStartTheAuthorizationExchange() {
		this.webTestClient.get()
			.uri("/oauth2/authorization/keycloak")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.value(HttpHeaders.LOCATION, (location) -> assertThat(location).startsWith("https://idp.example.com/auth")
				.contains("client_id=console"));
	}

	/**
	 * The registration a gateway application signing into an identity provider configures
	 * through {@code spring.security.oauth2.client.registration.*}.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class ClientRegistrationConfiguration {

		@Bean
		ReactiveClientRegistrationRepository clientRegistrationRepository() {
			return new InMemoryReactiveClientRegistrationRepository(ClientRegistration.withRegistrationId("keycloak")
				.clientName("Acme")
				.clientId("console")
				.clientSecret("secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.scope("openid", "profile")
				.authorizationUri("https://idp.example.com/auth")
				.tokenUri("https://idp.example.com/token")
				.userInfoUri("https://idp.example.com/userinfo")
				.userNameAttributeName("sub")
				.jwkSetUri("https://idp.example.com/jwks")
				.build());
		}

	}

}
