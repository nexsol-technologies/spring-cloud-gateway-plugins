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
 * A gateway registering several usable providers can still name the ones its console
 * signs in through: the others are registered for the traffic it routes, and an operator
 * has no business choosing between them on a login page.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.use=back-office" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiLoginProvidersUseTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldOfferTheNamedRegistrationAlone() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("/oauth2/authorization/back-office")
				.contains("Sign in with Back office")
				.doesNotContain("/oauth2/authorization/partners"));
	}

	@Test
	void shouldStartTheExchangeOfTheNamedRegistration() {
		this.webTestClient.get().uri("/oauth2/authorization/back-office").exchange().expectStatus().isFound();
	}

	@Test
	void shouldNotStartAnExchangeForTheRegistrationItLeftOut() {
		this.webTestClient.get().uri("/oauth2/authorization/partners").exchange().expectStatus().isBadRequest();
	}

	/**
	 * Two providers a browser can sign in through, only one of which the console is meant
	 * to offer.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class ClientRegistrationConfiguration {

		@Bean
		ReactiveClientRegistrationRepository clientRegistrationRepository() {
			return new InMemoryReactiveClientRegistrationRepository(registration("back-office", "Back office"),
					registration("partners", "Partners"));
		}

		private static ClientRegistration registration(String id, String name) {
			return ClientRegistration.withRegistrationId(id)
				.clientName(name)
				.clientId(id)
				.clientSecret("secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.scope("openid", "profile")
				.authorizationUri("https://idp.example.com/auth")
				.tokenUri("https://idp.example.com/token")
				.userInfoUri("https://idp.example.com/userinfo")
				.userNameAttributeName("sub")
				.jwkSetUri("https://idp.example.com/jwks")
				.build();
		}

	}

}
