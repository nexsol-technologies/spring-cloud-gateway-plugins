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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.nexsol.gateway.commons.security.SecuredPaths;
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
 * The local user of the console has to remain a way in on a gateway running its plugins:
 * paths contributed by another plugin, a Bearer token accepted on the endpoints, and an
 * identity provider registered for the routed traffic all land on the same chain as the
 * credentials form, and none of them may take it over.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.user.name=superadmin",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret",
		"spring.cloud.gateway.server.webflux.ui.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:59999/realms/absent" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiLocalLoginWithPluginsTests {

	private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldStillOfferTheCredentialsFormNextToTheProvider() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("name=\"username\"").contains("/oauth2/authorization/keycloak"));
	}

	@Test
	void shouldAcceptTheLocalUser() {
		EntityExchangeResult<String> page = this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectBody(String.class)
			.returnResult();
		Matcher token = CSRF.matcher(page.getResponseBody());
		assertThat(token.find()).as("the login page carries a CSRF token").isTrue();

		this.webTestClient.post()
			.uri("/ui/login")
			.cookie("SESSION", page.getResponseCookies().getFirst("SESSION").getValue())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData("username", "superadmin")
				.with("password", "console-secret")
				.with("_csrf", token.group(1)))
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui");
	}

	@Test
	void shouldOpenTheShellWithTheSessionTheLocalUserSignedInWith() {
		this.webTestClient.get().uri("/ui").cookie("SESSION", signIn()).exchange().expectStatus().isOk();
	}

	@Test
	void shouldServeThePathsOfTheOtherPluginsWithThatSameSession() {
		this.webTestClient.get()
			.uri("/plugin/browsed")
			.cookie("SESSION", signIn())
			.exchange()
			.expectStatus()
			.isNotFound();
	}

	private String signIn() {
		EntityExchangeResult<String> page = this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
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
			.returnResult(String.class)
			.getResponseCookies()
			.getFirst("SESSION")
			.getValue();
	}

	/**
	 * What a gateway running its plugins puts on the same chain: the paths the OpenAPI
	 * hub declares to the console, and the client the routed traffic is registered with.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class RunningPluginsConfiguration {

		@Bean
		SecuredPaths hubOpenapiSecuredPaths() {
			return SecuredPaths.governed("/plugin/browsed");
		}

		@Bean
		ReactiveClientRegistrationRepository clientRegistrationRepository() {
			return new InMemoryReactiveClientRegistrationRepository(ClientRegistration.withRegistrationId("keycloak")
				.clientName("Acme")
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
