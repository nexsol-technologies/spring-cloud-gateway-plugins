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
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The login page offers a button only for the registrations a browser could actually sign
 * in through. A gateway registers its clients for the traffic it routes as much as for
 * its console, and the ones granted tokens without a user &mdash; a client credentials
 * client relaying tokens downstream &mdash; have no authorization endpoint to send anyone
 * to.
 */
@SpringBootTest(properties = "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated")
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiLoginProvidersFilterTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldOfferTheAuthorizationCodeRegistration() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("/oauth2/authorization/portal").contains("Sign in with Portal"));
	}

	@Test
	void shouldLeaveOutTheRegistrationNoBrowserCouldComplete() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectBody(String.class)
			.value((body) -> assertThat(body).doesNotContain("/oauth2/authorization/relay")
				.doesNotContain("Sign in with Token relay"));
	}

	@Test
	void shouldNotStartAnExchangeForTheRegistrationItLeftOut() {
		this.webTestClient.get().uri("/oauth2/authorization/relay").exchange().expectStatus().isBadRequest();
	}

	/**
	 * What a gateway signing its console into a provider and relaying tokens to its
	 * services registers: one client per purpose, only one of which is a way in.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class ClientRegistrationConfiguration {

		@Bean
		ReactiveClientRegistrationRepository clientRegistrationRepository() {
			return new InMemoryReactiveClientRegistrationRepository(
					ClientRegistration.withRegistrationId("portal")
						.clientName("Portal")
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
						.build(),
					ClientRegistration.withRegistrationId("relay")
						.clientName("Token relay")
						.clientId("gateway")
						.clientSecret("secret")
						.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
						.tokenUri("https://idp.example.com/token")
						.build());
		}

	}

}
