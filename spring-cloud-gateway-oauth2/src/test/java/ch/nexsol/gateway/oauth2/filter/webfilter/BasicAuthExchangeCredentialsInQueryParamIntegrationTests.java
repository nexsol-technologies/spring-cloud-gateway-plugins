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

package ch.nexsol.gateway.oauth2.filter.webfilter;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import ch.nexsol.gateway.oauth2.filter.BaseWebClientTests;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Integration tests for the opt-in credentials query parameter of
 * {@link BasicAuthExchangeToAccessTokenGatewayWebFilter}, exercised against the
 * {@code basicauth-query-param} profile which turns it on.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("basicauth-query-param")
class BasicAuthExchangeCredentialsInQueryParamIntegrationTests extends BaseWebClientTests {

	private static final String CLIENT_ID = "my-client";

	private static final String CLIENT_SECRET = "my-secret";

	private static final String CREDENTIALS = Base64.getEncoder()
		.encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes());

	private static final String DOWNSTREAM_URI = "/api/resource";

	@Autowired
	public MockWebServer mockOAuthServer;

	@Autowired
	public CacheManager cacheManager;

	private String accessToken;

	private int initialRequestCount;

	@BeforeEach
	void setUp() {
		this.initialRequestCount = this.mockOAuthServer.getRequestCount();
		this.mockOAuthServer.setDispatcher(new okhttp3.mockwebserver.QueueDispatcher());
		this.cacheManager.getCache("basicauth-token-exchange.cache").clear();
		this.accessToken = plainJwtExpiringIn(Duration.ofHours(1));
	}

	@Test
	void should_exchange_credentials_read_from_the_query_param_and_strip_it() {
		// Arrange
		enqueueToken();

		// Act & Assert
		this.testClient.get()
			.uri((builder) -> builder.path(DOWNSTREAM_URI).queryParam("_auth", CREDENTIALS).build())
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith((result) -> {
				Map<String, ?> body = result.getResponseBody();
				assertThat(body.get("headers").toString()).contains("Bearer " + this.accessToken);
				// The credentials stop at the gateway: they must not reach the
				// downstream service, nor its access log
				assertThat(body.get("query").toString()).doesNotContain("_auth", CREDENTIALS);
			});

		assertThat(this.mockOAuthServer.getRequestCount() - this.initialRequestCount).isEqualTo(1);
	}

	@Test
	void should_keep_the_other_query_params_when_stripping_the_credentials() {
		// Arrange
		enqueueToken();

		// Act & Assert
		this.testClient.get()
			.uri((builder) -> builder.path(DOWNSTREAM_URI)
				.queryParam("page", "2")
				.queryParam("_auth", CREDENTIALS)
				.queryParam("size", "10")
				.build())
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith((result) -> {
				Map<String, ?> body = result.getResponseBody();
				assertThat(body.get("query").toString()).contains("page", "2", "size", "10").doesNotContain("_auth");
			});
	}

	@Test
	void should_still_exchange_from_the_authorization_header_when_there_is_no_query_param() {
		// Arrange: the option only adds a fallback, it never turns the header off
		enqueueToken();

		// Act & Assert
		this.testClient.get()
			.uri(DOWNSTREAM_URI)
			.header(HttpHeaders.AUTHORIZATION, "Basic " + CREDENTIALS)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith((result) -> assertThat(result.getResponseBody().get("headers").toString())
				.contains("Bearer " + this.accessToken));

		assertThat(this.mockOAuthServer.getRequestCount() - this.initialRequestCount).isEqualTo(1);
	}

	@Test
	void should_prefer_the_authorization_header_over_the_query_param() {
		// Arrange: a usable header, and a query parameter naming another client
		enqueueToken();
		String otherCredentials = Base64.getEncoder().encodeToString("other-client:other-secret".getBytes());

		// Act
		this.testClient.get()
			.uri((builder) -> builder.path(DOWNSTREAM_URI).queryParam("_auth", otherCredentials).build())
			.header(HttpHeaders.AUTHORIZATION, "Basic " + CREDENTIALS)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith((result) -> assertThat(result.getResponseBody().get("headers").toString())
				.contains("Bearer " + this.accessToken));

		// Assert: the exchange used the client id of the header
		assertThat(this.mockOAuthServer.getRequestCount() - this.initialRequestCount).isEqualTo(1);
	}

	@Test
	void should_leave_the_request_alone_when_the_query_param_names_an_unknown_client() {
		// Arrange
		String unknown = Base64.getEncoder().encodeToString("unknown-client:secret".getBytes());

		// Act & Assert
		this.testClient.get()
			.uri((builder) -> builder.path(DOWNSTREAM_URI).queryParam("_auth", unknown).build())
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith((result) -> {
				Map<String, ?> body = result.getResponseBody();
				assertThat(body.get("headers").toString()).doesNotContain("Bearer");
				assertThat(body.get("query").toString()).contains(unknown);
			});

		assertThat(this.mockOAuthServer.getRequestCount() - this.initialRequestCount).isEqualTo(0);
	}

	@Test
	void should_leave_the_request_alone_when_the_query_param_is_not_valid_base64() {
		// Act & Assert
		this.testClient.get()
			.uri((builder) -> builder.path(DOWNSTREAM_URI).queryParam("_auth", "not-base64!!").build())
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith((result) -> assertThat(result.getResponseBody().get("headers").toString())
				.doesNotContain("Bearer"));

		assertThat(this.mockOAuthServer.getRequestCount() - this.initialRequestCount).isEqualTo(0);
	}

	private void enqueueToken() {
		this.mockOAuthServer.enqueue(new MockResponse().setResponseCode(200)
			.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.setBody("""
					{
						"access_token": "%s",
						"token_type": "Bearer",
						"expires_in": 3600
					}
					""".formatted(this.accessToken)));
	}

	private static String plainJwtExpiringIn(Duration validity) {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().expirationTime(Date.from(Instant.now().plus(validity)))
			.build();
		return new PlainJWT(claims).serialize();
	}

}
