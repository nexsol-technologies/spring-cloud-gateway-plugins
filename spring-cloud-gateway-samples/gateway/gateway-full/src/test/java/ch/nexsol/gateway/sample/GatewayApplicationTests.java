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

package ch.nexsol.gateway.sample;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.nexsol.gateway.ui.security.GatewayUiSecurityProperties;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is worth asserting on the sample running every plugin at once is that they
 * coexist: the context starts with all of them on the classpath, the sources aggregate,
 * and the shell serves the views they light up.
 * <p>
 * The sources reaching outside are turned off — a contract fetched over the network and a
 * service registry are what the sample needs when it is run, not when it is tested.
 */
@SpringBootTest(properties = { "eureka.client.enabled=false",
		"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false" })
@AutoConfigureWebTestClient
class GatewayApplicationTests {

	private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

	@Autowired
	RouteDefinitionLocator routeDefinitionLocator;

	@Autowired
	WebTestClient webTestClient;

	@Autowired
	GatewayUiSecurityProperties consoleSecurity;

	@Autowired
	OAuth2ResourceServerProperties routedTrafficSecurity;

	@Test
	void shouldAggregateThePropertiesAndTheFileSources() {
		StepVerifier.create(this.routeDefinitionLocator.getRouteDefinitions().map(RouteDefinition::getId).collectList())
			.assertNext((ids) -> assertThat(ids).contains("test-authorization", "files_httpbin_route"))
			.verifyComplete();
	}

	@Test
	void shouldKeepTheConsoleBehindItsLoginPage() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?unauthorized");
		/*
		 * The database routes page belongs to another plugin, which declares it as an
		 * endpoint that changes the gateway. The chain of the console asks for a
		 * principal whatever its mode, and sends the visitor to the same login page.
		 */
		this.webTestClient.get()
			.uri("/ui/routes/db")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?unauthorized");
	}

	@Test
	void shouldKeepTheRouteManagementApiBehindThatSameLogin() {
		/*
		 * This application declares no rule for it. The plugin serving it declares it as
		 * an endpoint that changes the gateway, and the chain of the console — ordered
		 * ahead of the ones here — is what asks for a principal.
		 */
		this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?unauthorized");
		this.webTestClient.get()
			.uri("/api/gateway/routes")
			.cookie("SESSION", signIn())
			.exchange()
			.expectStatus()
			.isOk();
	}

	@Test
	void shouldKeepTheIssuerOfTheConsoleApartFromTheOneOfTheRoutedTraffic() {
		/*
		 * The two resource servers of a gateway are easy to confuse:
		 * spring.security.oauth2.resourceserver holds a single issuer for the whole
		 * application and belongs to the traffic being routed, while the console names
		 * its own. Both are wired here, and this context starting at all says the console
		 * does not wait on its provider: the keys are fetched on the first token that
		 * arrives.
		 *
		 * What a token then buys is exercised against the running authorization server,
		 * as the README of this sample shows — not here, where nothing is listening on
		 * 9090.
		 */
		assertThat(this.consoleSecurity.getOauth2().getResourceserver().getJwt().getIssuerUri()).isNotBlank();
		assertThat(this.routedTrafficSecurity.getJwt().getIssuerUri()).isNotBlank();
	}

	@Test
	void shouldServeTheViewsEveryPluginLightsUpOnceSignedIn() {
		String session = signIn();
		for (String view : new String[] { "/ui", "/ui/routes", "/ui/routes/db", "/ui/metrics", "/ui/audit" }) {
			this.webTestClient.get().uri(view).cookie("SESSION", session).exchange().expectStatus().isOk();
		}
	}

	/**
	 * Signs the local user of the console in and returns the session it opened. The
	 * authentication changes the session id, so the cookie that matters is the one the
	 * {@code POST} handed back, not the one the login page was served under.
	 * @return the authenticated session id
	 */
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
				.with("password", "superadmin")
				.with("_csrf", token.group(1)))
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui")
			.expectBody()
			.isEmpty()
			.getResponseCookies()
			.getFirst("SESSION")
			.getValue();
	}

}
