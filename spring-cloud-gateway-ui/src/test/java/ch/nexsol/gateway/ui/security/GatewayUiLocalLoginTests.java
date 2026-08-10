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
 * Tests the console under {@code mode=authenticated} with a local user: what an
 * unauthenticated visitor gets, in the terms of the request they made, and that signing
 * in with the credentials form actually opens the shell.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.user.name=operator",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiLocalLoginTests {

	private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldSendAnAnonymousVisitorToTheLoginPage() {
		this.webTestClient.get().uri("/ui").exchange().expectStatus().isFound().expectHeader().location("/ui/login");
	}

	@Test
	void shouldServeTheLoginPageWithItsCredentialsForm() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_HTML)
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("action=\"/ui/login\"")
				.contains("name=\"username\"")
				.contains("name=\"password\"")
				.contains("name=\"_csrf\"")
				// No provider is registered here, so no button and no divider.
				.doesNotContain("/oauth2/authorization/"));
	}

	@Test
	void shouldServeTheAssetsTheLoginPagePaintsWith() {
		for (String asset : new String[] { "/css/bootstrap.min.css", "/css/gateway-ui.css", "/img/logo.png",
				"/img/icon.png" }) {
			this.webTestClient.get().uri(asset).exchange().expectStatus().isOk();
		}
	}

	@Test
	void shouldTellHtmxToLeaveTheShellRatherThanSwapTheLoginPageIn() {
		this.webTestClient.get()
			.uri("/ui")
			.header("HX-Request", "true")
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.valueEquals("HX-Redirect", "/ui/login");
	}

	@Test
	void shouldAnswerAnEventStreamSubscriptionWithUnauthorized() {
		this.webTestClient.get()
			.uri("/ui")
			.accept(MediaType.TEXT_EVENT_STREAM)
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.doesNotExist(HttpHeaders.LOCATION);
	}

	@Test
	void shouldAnswerATokenBearingRequestWithUnauthorizedRatherThanAnHtmlPage() {
		this.webTestClient.get()
			.uri("/ui")
			.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
	}

	@Test
	void shouldOpenTheShellOnceTheCredentialsAreAccepted() {
		EntityExchangeResult<String> page = this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult();
		String session = page.getResponseCookies().getFirst("SESSION").getValue();
		String token = csrfToken(page.getResponseBody());

		this.webTestClient.post()
			.uri("/ui/login")
			.cookie("SESSION", session)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData("username", "operator")
				.with("password", "console-secret")
				.with("_csrf", token))
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui");
	}

	@Test
	void shouldSendWrongCredentialsBackToTheLoginPage() {
		EntityExchangeResult<String> page = this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectBody(String.class)
			.returnResult();
		String session = page.getResponseCookies().getFirst("SESSION").getValue();

		this.webTestClient.post()
			.uri("/ui/login")
			.cookie("SESSION", session)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData("username", "operator")
				.with("password", "wrong")
				.with("_csrf", csrfToken(page.getResponseBody())))
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?error");
	}

	@Test
	void shouldRejectAFormPostThatCarriesNoCsrfToken() {
		this.webTestClient.post()
			.uri("/ui/login")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData("username", "operator").with("password", "console-secret"))
			.exchange()
			.expectStatus()
			.isForbidden();
	}

	private static String csrfToken(String body) {
		Matcher matcher = CSRF.matcher(body);
		assertThat(matcher.find()).as("the login page carries a CSRF token").isTrue();
		return matcher.group(1);
	}

}
