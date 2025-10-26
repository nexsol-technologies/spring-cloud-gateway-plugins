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

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ch.nexsol.gateway.oauth2.filter.BaseWebClientTests;
import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import io.micrometer.observation.tck.TestObservationRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("basicauth-to-accesstoken")
class BasicAuthExchangeToAccessTokenGatewayWebFilterIntegrationTests extends BaseWebClientTests {

	private static final String CLIENT_ID = "my-client";

	private static final String CLIENT_SECRET = "my-secret";

	private static final String VALID_BASIC_AUTH_HEADER = "Basic "
			+ Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes());

	private static final String DOWNSTREAM_URI = "/api/resource";

	private static AtomicReference<String> VALID_ACCESS_TOKEN = new AtomicReference<>();

	private static AtomicReference<String> EXPIRED_ACCESS_TOKEN = new AtomicReference<>();

	@Autowired
	public MockWebServer mockOAuthServer;

	@Autowired
	public CacheManager cacheManager;

	private TestObservationRegistry observationRegistry;

	private int initialRequestCount;

	// @BeforeAll
	// static void setUpAll() throws IOException {
	// mockOAuthServer.start();
	// }
	//
	// @AfterAll
	// static void tearDownAll() throws IOException {
	// mockOAuthServer.shutdown();
	// }

	@BeforeEach
	void setUp() {

		this.initialRequestCount = this.mockOAuthServer.getRequestCount();

		this.mockOAuthServer.setDispatcher(new okhttp3.mockwebserver.QueueDispatcher());

		// The CacheManager must be reset before each test
		this.cacheManager.getCache("basicauth-token-exchange.cache").clear();
		this.observationRegistry = TestObservationRegistry.create();

		// Generate fresh tokens for each test
		VALID_ACCESS_TOKEN.set(generatePlainJwt(Instant.now().plus(Duration.ofHours(1))));
		// Expired token (in the past), taking into account the filter's 30-second margin.
		EXPIRED_ACCESS_TOKEN.set(generatePlainJwt(Instant.now().minus(Duration.ofSeconds(60))));
	}

	@Test
	void should_skip_filter_for_actuator_path() {
		this.testClient.get()
			.uri("/actuator/health")
			.header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH_HEADER)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith((result) -> {
				Map<String, ?> body = result.getResponseBody();
				assertThat(body.get("status").toString()).contains("UP");
			});

		// Verify that the OAuth server was not called
		assertThat(this.mockOAuthServer.getRequestCount() - this.initialRequestCount).isEqualTo(0);
	}

	@Test
	void should_exchange_basic_auth_to_bearer_token_on_first_request() {
		// Arrange
		this.mockOAuthServer.enqueue(new MockResponse().setResponseCode(200)
			.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.setBody(createTokenResponse(VALID_ACCESS_TOKEN.get())));

		// Act & Assert
		this.testClient.get()
			.uri(DOWNSTREAM_URI)
			.header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH_HEADER)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith((result) -> {
				// Verify that the Basic header was replaced by Bearer on the downstream
				// request
				Map<String, ?> body = result.getResponseBody();
				assertThat(body.get("headers").toString()).contains("Bearer " + VALID_ACCESS_TOKEN.get());
			});

		// Verify that the OAuth server was called
		assertThat(this.mockOAuthServer.getRequestCount() - this.initialRequestCount).isEqualTo(1);

		// Verify that the request to the OAuth server was correct
		try {
			assertThat(this.mockOAuthServer.takeRequest().getBody().readUtf8()).contains("client_id=" + CLIENT_ID,
					"client_secret=" + CLIENT_SECRET,
					"grant_type=" + AuthorizationGrantType.CLIENT_CREDENTIALS.getValue());
		}
		catch (InterruptedException ex) {
			throw new RuntimeException("MockWebServer request analysis interrupted", ex);
		}
	}

	@Test
	void should_fall_through_chain_when_client_is_not_configured() {
		// Arrange: The filter is configured with 'my-client', we send 'unknown-client'
		String unknownClientBasicAuth = "Basic "
				+ Base64.getEncoder().encodeToString("unknown-client:secret".getBytes());

		// Act & Assert
		this.testClient.get()
			.uri(DOWNSTREAM_URI)
			.header(HttpHeaders.AUTHORIZATION, unknownClientBasicAuth)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith((result) -> {
				Map<String, Object> body = result.getResponseBody();
				assertThat(body.get("headers").toString()).contains(unknownClientBasicAuth);
			});

		// Verify that the OAuth server was NOT called
		assertThat(this.mockOAuthServer.getRequestCount() - this.initialRequestCount).isEqualTo(0);
	}

	@Test
	void should_use_cached_token_and_not_call_oauth_server_second_time() {
		// Arrange
		this.mockOAuthServer.enqueue(new MockResponse().setResponseCode(200)
			.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.setBody(createTokenResponse(VALID_ACCESS_TOKEN.get())));

		// 1. First request (Should call the OAuth server and cache the non-expired token)
		this.testClient.get()
			.uri(DOWNSTREAM_URI)
			.header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH_HEADER)
			.exchange()
			.expectStatus()
			.isOk();

		// Check if exactly one request was made so far
		assertThat(this.mockOAuthServer.getRequestCount() - this.initialRequestCount).isEqualTo(1);

		// Consume the first request (important for subsequent takeRequest checks)
		try {
			this.mockOAuthServer.takeRequest(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			throw new RuntimeException("MockWebServer request analysis interrupted", ex);
		}

		// Store the count after the first call and consumption
		int countAfterFirstCall = this.mockOAuthServer.getRequestCount();

		// 2. Second request (Should use the cache and NOT call the OAuth server)
		this.testClient.get()
			.uri(DOWNSTREAM_URI)
			.header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH_HEADER)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith((result) -> {
				// Verify that the Bearer token was used on the downstream request
				Map<String, Object> body = result.getResponseBody();
				assertThat(body.get("headers").toString()).contains("Bearer " + VALID_ACCESS_TOKEN.get());
			});

		// The request count should remain the same as after the first call (i.e., total
		// delta of 1)
		assertThat(this.mockOAuthServer.getRequestCount()).isEqualTo(countAfterFirstCall);
	}

	@Test
	void should_re_exchange_token_when_cached_token_is_expired() {
		// Arrange

		// Token that will be returned the first time (and cached, then judged expired by
		// the filter)
		String firstToken = EXPIRED_ACCESS_TOKEN.get();
		// Token that will be returned the second time (after re-exchange)
		String newToken = VALID_ACCESS_TOKEN.get();

		// 1. Enqueue the first response (for initial caching of the expired token)
		this.mockOAuthServer.enqueue(new MockResponse().setResponseCode(200)
			.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.setBody(createTokenResponse(firstToken)));

		// 2. Enqueue the second response (for token re-exchange)
		this.mockOAuthServer.enqueue(new MockResponse().setResponseCode(200)
			.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.setBody(createTokenResponse(newToken)));

		// 1. First request (Caching of the expired token)
		this.testClient.get()
			.uri(DOWNSTREAM_URI)
			.header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH_HEADER)
			.exchange()
			.expectStatus()
			.isOk();

		// 2. Second request (Token is expired, re-exchange should occur)
		this.testClient.get()
			.uri(DOWNSTREAM_URI)
			.header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH_HEADER)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith((result) -> {
				// Verify that the NEW token was used on the downstream request
				Map<String, Object> body = result.getResponseBody();
				assertThat(body.get("headers").toString()).contains("Bearer " + newToken);
			});

		// The OAuth server must have been called TWICE (total delta of 2)
		assertThat(this.mockOAuthServer.getRequestCount() - this.initialRequestCount).isEqualTo(2);

		// Clear mock server requests
		try {
			this.mockOAuthServer.takeRequest(2, TimeUnit.SECONDS);
			this.mockOAuthServer.takeRequest(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			throw new RuntimeException("MockWebServer request analysis interrupted", ex);
		}
	}

	/**
	 * Creates a Plain (unsigned) JWT string with a controlled expiration date. This
	 * method allows the filter's internal logic to use the real JWTParser.
	 * @param expirationTime the desired expiration instant.
	 * @return the serialized JWT string.
	 */
	private static String generatePlainJwt(Instant expirationTime) {
		try {
			// The filter simply checks for the presence and value of the 'exp' field.
			JWTClaimsSet claims = new JWTClaimsSet.Builder().expirationTime(Date.from(expirationTime)).build();
			return new PlainJWT(claims).serialize();
		}
		catch (Exception ex) {
			throw new RuntimeException("Failed to generate test JWT", ex);
		}
	}

	/**
	 * Creates a valid JSON response for the OAuth2 token exchange.
	 * @param accessToken the token to include in the response.
	 * @return the JSON string of the response.
	 */
	private String createTokenResponse(String accessToken) {
		// The expires_in time here is irrelevant, as the filter uses the expiration
		// from the JWT itself (claims.getExpirationTime()).
		return String.format("""
				{
					"access_token": "%s",
					"token_type": "Bearer",
					"expires_in": 3600
				}
				""", accessToken);
	}

	/**
	 * Helper to create an instance of BasicAuthExchangeToAccessTokenGatewayWebFilter.
	 */
	private BasicAuthExchangeToAccessTokenGatewayWebFilter createFilter() {
		String tokenUri = this.mockOAuthServer.url("/oauth/token").toString();

		// --- Minimal implementation of properties for filter initialization ---
		BasicAuthExchangeToAccessTokenProperties properties = new BasicAuthExchangeToAccessTokenProperties() {
			@Override
			public boolean isUserConfigured(String clientId) {
				return CLIENT_ID.equals(clientId);
			}

			@Override
			public Map<String, URI> getTokenUris() {
				return Map.of(CLIENT_ID, URI.create(tokenUri));
			}
		};
		// -----------------------------------------------------------------------------------------

		return new BasicAuthExchangeToAccessTokenGatewayWebFilter(properties, this.cacheManager,
				this.observationRegistry);
	}

}
