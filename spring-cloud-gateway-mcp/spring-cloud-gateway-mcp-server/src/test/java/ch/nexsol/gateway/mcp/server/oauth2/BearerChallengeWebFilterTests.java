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

package ch.nexsol.gateway.mcp.server.oauth2;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebHandler;

class BearerChallengeWebFilterTests {

	private static final String METADATA = "resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"";

	private final BearerChallengeWebFilter filter = new BearerChallengeWebFilter(
			new ProtectedResource(null, "/mcp", List.of("https://idp/realms/x"), List.of()), List.of("/mcp"));

	@Test
	void completesABareUnauthorizedWithTheChallenge() {
		client(status(HttpStatus.UNAUTHORIZED, null)).post()
			.uri("/mcp")
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer " + METADATA);
	}

	@Test
	void keepsWhatTheChainSaidAboutTheToken() {
		client(status(HttpStatus.UNAUTHORIZED, "Bearer error=\"invalid_token\"")).post()
			.uri("/mcp")
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\", " + METADATA);
	}

	@Test
	void leavesTheOtherStatusesAlone() {
		client(status(HttpStatus.OK, null)).post()
			.uri("/mcp")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.doesNotExist(HttpHeaders.WWW_AUTHENTICATE);
	}

	@Test
	void leavesTheOtherPathsAlone() {
		client(status(HttpStatus.UNAUTHORIZED, null)).get()
			.uri("/ui/metrics/data")
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.doesNotExist(HttpHeaders.WWW_AUTHENTICATE);
	}

	private WebTestClient client(WebHandler handler) {
		return WebTestClient.bindToWebHandler(handler)
			.webFilter(this.filter)
			.configureClient()
			.baseUrl("http://localhost")
			.build();
	}

	private static WebHandler status(HttpStatus status, String challenge) {
		return (ServerWebExchange exchange) -> {
			exchange.getResponse().setStatusCode(status);
			if (challenge != null) {
				exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
			}
			return exchange.getResponse().setComplete();
		};
	}

}
