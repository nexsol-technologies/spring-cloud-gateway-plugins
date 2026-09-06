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

package ch.nexsol.gateway.oauth2.autoconfigure;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The escape hatch of {@link BasicAuthExchangeSecurityAutoConfiguration}: a gateway that
 * would rather keep its own rules over the exchanged requests, to validate the resulting
 * token itself, turns the contributed chain off. The exchange still happens; only the
 * letting through is gone.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, classes = BasicAuthExchangeSecurityChainScopeTests.Application.class,
		properties = {
				"spring.cloud.gateway.server.webflux.webfilter.basicauth-exchange-oauth2."
						+ "token-uris.my-client=http://localhost:${custom.port}/token",
				"spring.cloud.gateway.server.webflux.webfilter.basicauth-exchange-oauth2."
						+ "security-chain-enabled=false" })
class BasicAuthExchangeSecurityChainDisabledTests {

	private static final String GOOD_CREDENTIALS = "Basic "
			+ Base64.getEncoder().encodeToString("my-client:my-secret".getBytes());

	@LocalServerPort
	int port;

	@Autowired
	MockWebServer mockOAuthServer;

	@Test
	void exchangedRequestFallsBackToTheChainsOfTheApplication() {
		String accessToken = new PlainJWT(
				new JWTClaimsSet.Builder().expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1)))).build())
			.serialize();
		this.mockOAuthServer.enqueue(new MockResponse().setResponseCode(200)
			.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.setBody("""
					{"access_token": "%s", "token_type": "Bearer", "expires_in": 3600}
					""".formatted(accessToken)));
		int before = this.mockOAuthServer.getRequestCount();

		WebTestClient.bindToServer()
			.baseUrl("http://localhost:" + this.port)
			.build()
			.get()
			.uri("/api/resource")
			.header(HttpHeaders.AUTHORIZATION, GOOD_CREDENTIALS)
			.exchange()
			.expectStatus()
			.isUnauthorized();

		// The exchange still ran, so the refusal is the application's own rule speaking
		assertThat(this.mockOAuthServer.getRequestCount() - before).isEqualTo(1);
	}

}
