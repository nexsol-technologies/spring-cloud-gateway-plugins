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

package ch.nexsol.gateway.sample.ui.secured;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.nexsol.gateway.ui.security.UiSessionCookieName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the sample under its default profile: the console is behind the login page,
 * and the local user gets through it. The {@code keycloak} profile is not exercised here,
 * since it needs the identity provider the compose file of the sample starts.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class SecuredUiGatewayApplicationTests {

	private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldSendAnAnonymousVisitorToTheLoginPage() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?unauthorized");
	}

	@Test
	void shouldOfferTheCredentialsFormAndNoProviderUnderTheDefaultProfile() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("name=\"username\"").doesNotContain("/oauth2/authorization/"));
	}

	@Test
	void shouldOpenTheConsoleForTheLocalUser() {
		EntityExchangeResult<String> page = this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult();
		String session = page.getResponseCookies().getFirst(UiSessionCookieName.COOKIE_NAME).getValue();
		Matcher token = CSRF.matcher(page.getResponseBody());
		assertThat(token.find()).as("the login page carries a CSRF token").isTrue();

		EntityExchangeResult<Void> signedIn = this.webTestClient.post()
			.uri("/ui/login")
			.cookie(UiSessionCookieName.COOKIE_NAME, session)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData("username", "superadmin")
				.with("password", "superadmin")
				.with("_csrf", token.group(1)))
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui")
			.expectBody()
			.isEmpty();
		// Signing in changes the session id, so the console is opened with the one the
		// authentication handed back rather than the one the login page was served under.
		String authenticated = signedIn.getResponseCookies().getFirst(UiSessionCookieName.COOKIE_NAME).getValue();

		this.webTestClient.get()
			.uri("/ui/routes")
			.cookie(UiSessionCookieName.COOKIE_NAME, authenticated)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("httpbin_get").contains("service_a"));
	}

}
