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

package ch.nexsol.service.sample.c;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this service puts in the response the gateway hands back to a browser, and why the
 * console cannot name its session cookie {@code SESSION}.
 * <p>
 * A browser opening the OpenAPI view fetches the aggregated contracts through the
 * gateway, which forwards its cookies to the service and relays the response back on the
 * same origin as the console. This service has no idea what the console's session id
 * means, and WebFlux answers an id it cannot resolve by telling the browser to drop the
 * cookie. The console is signed out by a call it made to read a contract.
 */
@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class ResourceServerCookieTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldServeItsContractWithoutCredentials() {
		// What the hub relies on: the gateway reads this with nothing to present.
		this.webTestClient.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
	}

	@Test
	void shouldSetNoCookieOnACallThatCarriesNone() {
		this.webTestClient.get().uri("/v3/api-docs").exchange().expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
	}

	@Test
	void shouldExpireASessionCookieItCannotResolve() {
		/*
		 * The whole mechanism, in one response. WebFlux answers a session id it cannot
		 * resolve by telling the browser to drop it, and the gateway relays that header
		 * on the same origin as its console. A console naming its cookie SESSION is
		 * therefore signed out by any service it routes a browser call to -- which is
		 * every call the OpenAPI view makes to fetch the aggregated contracts.
		 */
		this.webTestClient.get()
			.uri("/v3/api-docs")
			.cookie("SESSION", "an-id-nobody-knows")
			.exchange()
			.expectHeader()
			.value(HttpHeaders.SET_COOKIE,
					(cookie) -> assertThat(cookie).startsWith("SESSION=;").contains("Max-Age=0"));
	}

	@Test
	void shouldLeaveTheConsoleCookieAloneUnderItsOwnName() {
		// The same call, with the console's cookie named after the console: there is
		// nothing here that answers to that name, so nothing expires it.
		this.webTestClient.get()
			.uri("/v3/api-docs")
			.cookie("GATEWAY_CONSOLE_SESSION", "a-console-session")
			.exchange()
			.expectHeader()
			.doesNotExist(HttpHeaders.SET_COOKIE);
	}

}
