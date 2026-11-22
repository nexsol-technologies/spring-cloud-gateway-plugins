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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests what the exchange costs when the authorization server misbehaves, and what it
 * costs the requests that have nothing to exchange.
 * <p>
 * A request waiting for a token holds its connection: an authorization server that
 * accepts the call and never answers must not hold it for ever, nor pin the entry the
 * concurrent exchanges of the same credentials share.
 */
@ExtendWith(OutputCaptureExtension.class)
class BasicAuthExchangeResilienceTests {

	private static final String PREFIX = "exchange";

	private static final String CLIENT_ID = "my-client";

	private static final String CACHE_NAME = "basicauth-token-exchange.cache";

	private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

	private final WebFilterChain chain = (exchange) -> {
		this.forwarded.set(exchange);
		return Mono.empty();
	};

	private MockWebServer authorizationServer;

	@BeforeEach
	void startAuthorizationServer() throws IOException {
		this.authorizationServer = new MockWebServer();
		this.authorizationServer.start();
	}

	@AfterEach
	void stopAuthorizationServer() throws IOException {
		this.authorizationServer.shutdown();
	}

	@Test
	void givesUpOnATokenEndpointThatNeverAnswers() {
		this.authorizationServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		BasicAuthExchangeToAccessTokenGatewayWebFilter filter = filter(Map.of("token-timeout", "300ms"));

		StepVerifier.create(filter.filter(basic("my-secret"), this.chain))
			.expectErrorSatisfies(BasicAuthExchangeResilienceTests::isUnauthorized)
			.verify(Duration.ofSeconds(5));

		assertThat(this.forwarded).hasValue(null);
	}

	@Test
	void anExchangeThatTimedOutDoesNotPinTheOnesThatFollow() {
		this.authorizationServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		this.authorizationServer.enqueue(token());
		BasicAuthExchangeToAccessTokenGatewayWebFilter filter = filter(Map.of("token-timeout", "300ms"));

		StepVerifier.create(filter.filter(basic("my-secret"), this.chain)).expectError().verify(Duration.ofSeconds(5));
		StepVerifier.create(filter.filter(basic("my-secret"), this.chain))
			.expectComplete()
			.verify(Duration.ofSeconds(5));

		assertThat(this.forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
			.startsWith("Bearer ");
	}

	@Test
	void givesUpQuicklyOnATokenEndpointNobodyListensOn() throws IOException {
		BasicAuthExchangeToAccessTokenGatewayWebFilter filter = filter(Map.of());
		// The port is known and nothing listens on it any more: every attempt is refused.
		this.authorizationServer.shutdown();

		StepVerifier.create(filter.filter(basic("my-secret"), this.chain))
			.expectErrorSatisfies(BasicAuthExchangeResilienceTests::isUnauthorized)
			.verify(Duration.ofSeconds(4));
	}

	@Test
	void reportsRefusedCredentialsWithoutAStackTrace(CapturedOutput output) {
		this.authorizationServer.enqueue(new MockResponse().setResponseCode(HttpStatus.UNAUTHORIZED.value())
			.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.setBody("{\"error\": \"invalid_client\"}"));
		BasicAuthExchangeToAccessTokenGatewayWebFilter filter = filter(Map.of());

		StepVerifier.create(filter.filter(basic("WRONG-SECRET"), this.chain))
			.expectErrorSatisfies(BasicAuthExchangeResilienceTests::isUnauthorized)
			.verify(Duration.ofSeconds(5));

		// A wrong secret is an outcome, not an incident: whoever sends one decides how
		// often, and a stack trace per attempt is a log the caller writes at will.
		assertThat(output.getAll()).contains(CLIENT_ID).doesNotContain("\tat ");
	}

	@Test
	void startsNoObservationForARequestThatHasNothingToExchange() {
		TestObservationRegistry observations = TestObservationRegistry.create();
		BasicAuthExchangeToAccessTokenGatewayWebFilter filter = filter(Map.of(), new ConcurrentMapCacheManager(),
				observations);

		filter.filter(exchange(null), this.chain).block(Duration.ofSeconds(5));
		filter.filter(exchange("Bearer some.opaque.token"), this.chain).block(Duration.ofSeconds(5));
		filter.filter(exchange(basicHeader("another-client", "secret")), this.chain).block(Duration.ofSeconds(5));

		TestObservationRegistryAssert.assertThat(observations).doesNotHaveAnyObservation();
		assertThat(this.forwarded.get()).isNotNull();
	}

	@Test
	void stillObservesTheExchangeOfConfiguredCredentials() {
		this.authorizationServer.enqueue(token());
		TestObservationRegistry observations = TestObservationRegistry.create();
		BasicAuthExchangeToAccessTokenGatewayWebFilter filter = filter(Map.of(), new ConcurrentMapCacheManager(),
				observations);

		filter.filter(basic("my-secret"), this.chain).block(Duration.ofSeconds(5));

		TestObservationRegistryAssert.assertThat(observations)
			.hasObservationWithNameEqualTo("BasicAuthExchange")
			.that()
			.hasBeenStopped();
	}

	@Test
	void keepsTheTokenWhenTheCacheManagerDoesNotKnowItsCache() {
		this.authorizationServer.enqueue(token());
		this.authorizationServer.enqueue(token());
		// A cache manager given its cache names answers null for any other one.
		CacheManager staticNames = new ConcurrentMapCacheManager("some-other-cache");
		assertThat(staticNames.getCache(CACHE_NAME)).isNull();
		BasicAuthExchangeToAccessTokenGatewayWebFilter filter = filter(Map.of(), staticNames, ObservationRegistry.NOOP);

		filter.filter(basic("my-secret"), this.chain).block(Duration.ofSeconds(5));
		filter.filter(basic("my-secret"), this.chain).block(Duration.ofSeconds(5));

		assertThat(this.authorizationServer.getRequestCount()).isEqualTo(1);
	}

	private BasicAuthExchangeToAccessTokenGatewayWebFilter filter(Map<String, String> settings) {
		return filter(settings, new ConcurrentMapCacheManager(), ObservationRegistry.NOOP);
	}

	private BasicAuthExchangeToAccessTokenGatewayWebFilter filter(Map<String, String> settings,
			CacheManager cacheManager, ObservationRegistry observations) {
		Map<String, String> source = new LinkedHashMap<>();
		source.put(PREFIX + ".token-uris." + CLIENT_ID, this.authorizationServer.url("/oauth/token").toString());
		settings.forEach((name, value) -> source.put(PREFIX + "." + name, value));
		// Bound the way the application binds them, so a setting is given by its name.
		BasicAuthExchangeToAccessTokenProperties properties = new Binder(new MapConfigurationPropertySource(source))
			.bind(PREFIX, BasicAuthExchangeToAccessTokenProperties.class)
			.get();
		return new BasicAuthExchangeToAccessTokenGatewayWebFilter(properties, cacheManager, observations,
				WebClient.builder());
	}

	private static MockServerWebExchange basic(String secret) {
		return exchange(basicHeader(CLIENT_ID, secret));
	}

	private static MockServerWebExchange exchange(String authorization) {
		MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("http://gateway.example/api/resource");
		if (authorization != null) {
			request.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return MockServerWebExchange.from(request.build());
	}

	private static String basicHeader(String clientId, String secret) {
		return "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes());
	}

	private static MockResponse token() {
		String accessToken = new PlainJWT(
				new JWTClaimsSet.Builder().expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1)))).build())
			.serialize();
		return new MockResponse().setResponseCode(200)
			.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.setBody("{\"access_token\": \"" + accessToken + "\", \"token_type\": \"Bearer\", \"expires_in\": 3600}");
	}

	private static void isUnauthorized(Throwable error) {
		assertThat(error).isInstanceOf(ResponseStatusException.class);
		assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

}
