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

package ch.nexsol.authserver.sample;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Tests the CORS answer a console served from another localhost port needs to read the
 * discovery document and exchange a code here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsTests {

	private static final String DISCOVERY = "/.well-known/openid-configuration";

	private static final String CONSOLE = "http://localhost:8181";

	@LocalServerPort
	private int port;

	private RestTestClient client;

	@BeforeEach
	void bindToTheRunningServer() {
		this.client = RestTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void allowsAConsoleOnAnotherLocalhostPortToReadTheDiscoveryDocument() {
		this.client.get()
			.uri(DISCOVERY)
			.header(HttpHeaders.ORIGIN, CONSOLE)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE);
	}

	@Test
	void answersThePreflightRatherThanRedirectingItToTheLoginPage() {
		// Left to the auto-configured chains, the preflight comes back as a 302 to the
		// login page and the browser drops the response it was guarding.
		this.client.options()
			.uri(DISCOVERY)
			.header(HttpHeaders.ORIGIN, CONSOLE)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE);
	}

	@Test
	void answersThePreflightOfTheTokenExchange() {
		this.client.options()
			.uri("/oauth2/token")
			.header(HttpHeaders.ORIGIN, CONSOLE)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,OPTIONS");
	}

	@Test
	void doesNotAllowAnOriginThatIsNotALocalhostPort() {
		this.client.get()
			.uri(DISCOVERY)
			.header(HttpHeaders.ORIGIN, "https://elsewhere.example.com")
			.exchange()
			.expectHeader()
			.doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
	}

}
