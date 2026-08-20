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
 * A {@code use} list that keeps none of the registrations of the application leaves the
 * console with no provider at all, and never with the list it was there to narrow.
 * Falling back on the registrations of the application because the narrowing came out
 * empty would put every one of them back on the login page &mdash; the opposite of what
 * was asked.
 * <p>
 * The credentials form is what is left, which is why the local user is configured here: a
 * console narrowed down to nothing must still have a way in.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret",
		"spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client.use=absent" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiLoginProvidersNarrowedToNoneTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldOfferNoProviderAtAll() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).doesNotContain("/oauth2/authorization/"));
	}

	@Test
	void shouldStillOfferTheCredentialsForm() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("name=\"username\""));
	}

	@Test
	void shouldNotStartAnExchangeForTheRegistrationsItNarrowedAway() {
		this.webTestClient.get().uri("/oauth2/authorization/portal").exchange().expectStatus().isNotFound();
	}

	/**
	 * A provider a browser could sign in through, which {@code use} names none of.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class ClientRegistrationConfiguration {

		@Bean
		ReactiveClientRegistrationRepository clientRegistrationRepository() {
			return new InMemoryReactiveClientRegistrationRepository(ClientRegistration.withRegistrationId("portal")
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
				.build());
		}

	}

}
