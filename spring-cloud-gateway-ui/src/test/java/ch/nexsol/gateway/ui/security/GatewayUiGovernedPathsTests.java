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
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests a console behind its login page, over the endpoints the other plugins declared:
 * what follows the login, what stays in front of it, and what the CSRF protection covers.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.user.name=operator",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret" })
@AutoConfigureWebTestClient
class GatewayUiGovernedPathsTests {

	private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldPutTheLoginPageInFrontOfWhatAnotherPluginBrowses() {
		this.webTestClient.get()
			.uri("/plugin/browsed")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?unauthorized");
	}

	@Test
	void shouldLeaveWhatTheSiblingInstancesPollInFrontOfIt() {
		// Declared open by the plugin serving it: the poll carries no credentials, and a
		// login page it cannot answer would only break the consolidation.
		this.webTestClient.get().uri("/plugin/polled").exchange().expectStatus().isNotFound();
	}

	@Test
	void shouldKeepTheEndpointsThatChangeTheGatewayBehindIt() {
		this.webTestClient.get()
			.uri("/plugin/routes")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?unauthorized");
	}

	@Test
	void shouldServeThemOnceSignedIn() {
		String session = signIn();
		this.webTestClient.get()
			.uri("/plugin/browsed")
			.cookie("SESSION", session)
			.exchange()
			.expectStatus()
			.isNotFound();
	}

	@Test
	void shouldAskAFormForItsCsrfToken() {
		this.webTestClient.post()
			.uri("/plugin/browsed")
			.cookie("SESSION", signIn())
			.exchange()
			.expectStatus()
			.isForbidden();
	}

	@Test
	void shouldNotAskAnApiForACsrfTokenItHasNoSessionToHold() {
		// The declared exemption: reaching the handler, which is not there, rather than
		// being turned away by the CSRF filter.
		this.webTestClient.post()
			.uri("/plugin/routes")
			.cookie("SESSION", signIn())
			.exchange()
			.expectStatus()
			.isNotFound();
	}

	/**
	 * Signs the local user in and returns the session that authentication opened.
	 * @return the authenticated session id
	 */
	private String signIn() {
		EntityExchangeResult<String> page = this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectBody(String.class)
			.returnResult();
		String session = page.getResponseCookies().getFirst("SESSION").getValue();
		Matcher matcher = CSRF.matcher(page.getResponseBody());
		assertThat(matcher.find()).as("the login page carries a CSRF token").isTrue();
		return this.webTestClient.post()
			.uri("/ui/login")
			.cookie("SESSION", session)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(BodyInserters.fromFormData("username", "operator")
				.with("password", "console-secret")
				.with("_csrf", matcher.group(1)))
			.exchange()
			.expectStatus()
			.isFound()
			.returnResult(String.class)
			.getResponseCookies()
			.getFirst("SESSION")
			.getValue();
	}

	/**
	 * A plugin declaring one path of each kind, as the OpenAPI hub, the discovery metrics
	 * and the route management do.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class ContributingPluginConfiguration {

		@Bean
		SecuredPaths browsedPaths() {
			return SecuredPaths.governed("/plugin/browsed");
		}

		@Bean
		SecuredPaths polledPaths() {
			return SecuredPaths.open("/plugin/polled");
		}

		@Bean
		SecuredPaths routeManagementPaths() {
			return SecuredPaths.api("/plugin/routes");
		}

	}

}
