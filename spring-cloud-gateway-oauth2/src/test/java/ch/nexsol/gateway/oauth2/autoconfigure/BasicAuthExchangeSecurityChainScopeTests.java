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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Tests delimiting what the security chain contributed by
 * {@link BasicAuthExchangeSecurityAutoConfiguration} takes away from the application.
 * <p>
 * The chain declares no authorization rule: the exchange is what authorizes, so every
 * request its matcher accepts is served unchecked until the exchange filter has run.
 * Anything the filter would skip must therefore not be matched at all, or the plugin
 * turns into a way around the security of the application.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, classes = BasicAuthExchangeSecurityChainScopeTests.Application.class,
		// The property must carry its key from the start: the auto-configuration
		// condition is evaluated before a DynamicPropertyRegistrar has published
		// anything, so only the port can come from the mock server.
		properties = { "spring.cloud.gateway.server.webflux.webfilter.basicauth-exchange-oauth2."
				+ "token-uris.my-client=http://localhost:${custom.port}/token" })
class BasicAuthExchangeSecurityChainScopeTests {

	private static final String CLIENT_ID = "my-client";

	private static final String GOOD_CREDENTIALS = basic(CLIENT_ID + ":my-secret");

	// The client id of a configured client is not a secret: it sits in the configuration
	// and is often guessable. Only the secret is one, and the matcher cannot check it.
	private static final String FORGED_CREDENTIALS = basic(CLIENT_ID + ":WRONG-SECRET");

	@LocalServerPort
	int port;

	@Autowired
	MockWebServer mockOAuthServer;

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void applicationChainStillProtectsActuatorWithoutCredentials() {
		client().get().uri("/actuator/health").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void configuredClientIdDoesNotOpenActuator() {
		client().get()
			.uri("/actuator/health")
			.header(HttpHeaders.AUTHORIZATION, FORGED_CREDENTIALS)
			.exchange()
			.expectStatus()
			.isUnauthorized();
	}

	@Test
	void applicationChainStillProtectsRoutesWithoutCredentials() {
		client().get().uri("/api/resource").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void configuredClientIdWithAWrongSecretIsRefused() {
		// The authorization server rejects the credentials, the exchange fails, and the
		// request is denied rather than forwarded with its raw Basic header
		this.mockOAuthServer.enqueue(new MockResponse().setResponseCode(HttpStatus.UNAUTHORIZED.value()));

		client().get()
			.uri("/api/resource")
			.header(HttpHeaders.AUTHORIZATION, FORGED_CREDENTIALS)
			.exchange()
			.expectStatus()
			.isUnauthorized();
	}

	@Test
	void exchangedRequestIsStillSubjectToTheAuthorizationRulesOfTheApplication() {
		// The exchange authenticates nobody: it swaps credentials for a bearer token and
		// forwards. An application demanding an authenticated principal, with nothing
		// wired to authenticate that token, refuses the request all the same.
		String accessToken = plainJwt(Instant.now().plus(Duration.ofHours(1)));
		this.mockOAuthServer.enqueue(new MockResponse().setResponseCode(200)
			.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.setBody("""
					{"access_token": "%s", "token_type": "Bearer", "expires_in": 3600}
					""".formatted(accessToken)));
		int before = this.mockOAuthServer.getRequestCount();

		client().get()
			.uri("/api/resource")
			.header(HttpHeaders.AUTHORIZATION, GOOD_CREDENTIALS)
			.exchange()
			.expectStatus()
			.isUnauthorized();

		// The exchange did run, so the refusal comes from the application, not from a
		// request the plugin failed to handle
		assertThat(this.mockOAuthServer.getRequestCount() - before).isEqualTo(1);
	}

	private static String basic(String pair) {
		return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes());
	}

	private static String plainJwt(Instant expiration) {
		return new PlainJWT(new JWTClaimsSet.Builder().expirationTime(Date.from(expiration)).build()).serialize();
	}

	/**
	 * An application that secures everything, the way a real gateway does, and declares
	 * nothing about the plugin.
	 */
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class Application {

		@Bean
		SecurityWebFilterChain applicationChain(ServerHttpSecurity http) {
			http.csrf(ServerHttpSecurity.CsrfSpec::disable);
			http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
			http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
			http.authorizeExchange((spec) -> spec.anyExchange().authenticated());
			return http.build();
		}

		@Bean(destroyMethod = "shutdown")
		MockWebServer mockOAuthServer() throws IOException {
			MockWebServer server = new MockWebServer();
			server.start();
			return server;
		}

		@Bean
		DynamicPropertyRegistrar tokenPortRegistrar(MockWebServer mockOAuthServer) {
			return (registry) -> registry.add("custom.port", mockOAuthServer::getPort);
		}

		@RestController
		static class Controller {

			@RequestMapping(path = "/api/resource", produces = MediaType.APPLICATION_JSON_VALUE)
			Map<String, Object> resource(ServerWebExchange exchange) {
				String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
				return Map.of("authorization", (authorization != null) ? authorization : "");
			}

		}

	}

}
