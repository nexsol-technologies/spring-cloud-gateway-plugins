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

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing out of a console that registered an identity provider ends the session the
 * provider holds too, not only the one the console holds &mdash; otherwise signing back
 * in hands the same account straight back and nobody can come back as somebody else.
 * <p>
 * That handler replaces the plain redirect for everybody, which is what this test is
 * about: the local user must still be signed out the ordinary way. The provider route is
 * taken for a principal that came from the provider, and this test has no provider to
 * sign into.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.user.name=superadmin",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiOidcLogoutTests {

	private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldStillSignTheLocalUserOutTheOrdinaryWay() {
		String session = signIn();
		EntityExchangeResult<String> shell = this.webTestClient.get()
			.uri("/ui")
			.cookie("SESSION", session)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult();
		Matcher token = CSRF.matcher(shell.getResponseBody());
		assertThat(token.find()).as("the shell carries a CSRF token").isTrue();

		this.webTestClient.post()
			.uri("/ui/logout")
			.cookie("SESSION", session)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData("_csrf", token.group(1)))
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?logout");
	}

	private String signIn() {
		EntityExchangeResult<String> page = this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult();
		Matcher token = CSRF.matcher(page.getResponseBody());
		assertThat(token.find()).as("the login page carries a CSRF token").isTrue();
		return this.webTestClient.post()
			.uri("/ui/login")
			.cookie("SESSION", page.getResponseCookies().getFirst("SESSION").getValue())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData("username", "superadmin")
				.with("password", "console-secret")
				.with("_csrf", token.group(1)))
			.exchange()
			.expectStatus()
			.isFound()
			.expectBody()
			.isEmpty()
			.getResponseCookies()
			.getFirst("SESSION")
			.getValue();
	}

	/**
	 * A registration carrying the end-session endpoint a provider publishes, so the
	 * console wires the provider-initiated logout in.
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
				.scope("openid")
				.authorizationUri("https://idp.example.com/auth")
				.tokenUri("https://idp.example.com/token")
				.jwkSetUri("https://idp.example.com/jwks")
				.userNameAttributeName("preferred_username")
				.providerConfigurationMetadata(Map.of("end_session_endpoint", "https://idp.example.com/logout"))
				.build());
		}

	}

}
