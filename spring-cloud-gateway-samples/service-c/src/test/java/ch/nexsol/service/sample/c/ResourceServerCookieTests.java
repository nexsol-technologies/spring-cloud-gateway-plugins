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

/**
 * What this service puts in the response the gateway hands back to a browser.
 * <p>
 * A browser opening the OpenAPI view of the console fetches the aggregated contracts
 * through the gateway, so these responses reach it on the same origin as the console. A
 * {@code Set-Cookie} here is therefore a cookie set on the console's origin, and one
 * named {@code SESSION} would replace the console's own if the console still used that
 * name.
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
	void shouldSetNoCookieOnTheContractItPublishes() {
		this.webTestClient.get().uri("/v3/api-docs").exchange().expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
	}

	@Test
	void shouldSetNoCookieWhenItRefusesACallForLackOfAToken() {
		this.webTestClient.get()
			.uri("/service-c/data")
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.doesNotExist(HttpHeaders.SET_COOKIE);
	}

	@Test
	void shouldSetNoCookieWhenACallCarriesTheConsoleSessionCookie() {
		// The gateway forwards the cookies of the browser to the services it routes to,
		// so
		// this service is handed the console's session cookie on every such call.
		this.webTestClient.get()
			.uri("/v3/api-docs")
			.cookie("GATEWAY_CONSOLE_SESSION", "a-console-session")
			.exchange()
			.expectHeader()
			.doesNotExist(HttpHeaders.SET_COOKIE);
	}

}
