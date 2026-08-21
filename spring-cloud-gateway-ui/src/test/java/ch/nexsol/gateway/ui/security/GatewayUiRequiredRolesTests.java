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

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests what a visitor the console turned away gets: they signed in, they hold none of
 * the roles the console asks for, and what they are shown has to leave them a way out. A
 * naked {@code 403} would not: signing in again hands back the same roles, and there is
 * no button on that page to sign out with.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.user.name=visitor",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=visitor-secret",
		"spring.cloud.gateway.server.webflux.ui.security.user.roles=READER",
		"spring.cloud.gateway.server.webflux.ui.security.required-roles=ADMIN" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiRequiredRolesTests {

	private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldLetTheVisitorSignInAndThenTurnThemAway() {
		String session = signIn();
		this.webTestClient.get()
			.uri("/ui")
			.cookie(UiSessionCookieName.COOKIE_NAME, session)
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/forbidden");
	}

	@Test
	void shouldExplainTheRefusalAndOfferTheWayOut() {
		this.webTestClient.get()
			.uri("/ui/forbidden")
			.cookie(UiSessionCookieName.COOKIE_NAME, signIn())
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("This console is not open to you")
				.contains("visitor")
				.contains("action=\"/ui/logout\""));
	}

	@Test
	void shouldLetTheVisitorSignOut() {
		String session = signIn();
		EntityExchangeResult<String> page = this.webTestClient.get()
			.uri("/ui/forbidden")
			.cookie(UiSessionCookieName.COOKIE_NAME, session)
			.exchange()
			.expectBody(String.class)
			.returnResult();
		Matcher token = CSRF.matcher(page.getResponseBody());
		assertThat(token.find()).as("the page carries a CSRF token").isTrue();

		this.webTestClient.post()
			.uri("/ui/logout")
			.cookie(UiSessionCookieName.COOKIE_NAME, session)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData("_csrf", token.group(1)))
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?logout");
	}

	@Test
	void shouldTellHtmxToLeaveTheShellRatherThanSwapTheRefusalIn() {
		this.webTestClient.get()
			.uri("/ui")
			.cookie(UiSessionCookieName.COOKIE_NAME, signIn())
			.header("HX-Request", "true")
			.exchange()
			.expectStatus()
			.isForbidden()
			.expectHeader()
			.valueEquals("HX-Redirect", "/ui/forbidden");
	}

	@Test
	void shouldAnswerATokenBearingRequestWithForbiddenRatherThanAnExplanationPage() {
		this.webTestClient.get()
			.uri("/ui")
			.cookie(UiSessionCookieName.COOKIE_NAME, signIn())
			.header(HttpHeaders.AUTHORIZATION, "Bearer a-token")
			.exchange()
			.expectStatus()
			.isForbidden()
			.expectHeader()
			.doesNotExist(HttpHeaders.LOCATION);
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
			.cookie(UiSessionCookieName.COOKIE_NAME,
					page.getResponseCookies().getFirst(UiSessionCookieName.COOKIE_NAME).getValue())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData("username", "visitor")
				.with("password", "visitor-secret")
				.with("_csrf", token.group(1)))
			.exchange()
			.expectStatus()
			.isFound()
			.expectBody()
			.isEmpty()
			.getResponseCookies()
			.getFirst(UiSessionCookieName.COOKIE_NAME)
			.getValue();
	}

}
