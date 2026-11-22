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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Tests that the chain contributed by {@link BasicAuthExchangeSecurityAutoConfiguration}
 * leaves the endpoints of the other plugins to the chains that guard them.
 * <p>
 * The console, the route management API, the passive scan, the OpenAPI hub and the MCP
 * endpoint each contribute a chain over their own paths, the last of them at
 * {@code HIGHEST_PRECEDENCE + 450}. A client whose Basic credentials were exchanged is an
 * API client, not an operator of the gateway: what it may reach on those paths is for
 * their chain to say.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, classes = BasicAuthExchangePluginChainTests.Application.class,
		properties = { "spring.cloud.gateway.server.webflux.webfilter.basicauth-exchange-oauth2."
				+ "token-uris.my-client=http://localhost:${custom.port}/token" })
class BasicAuthExchangePluginChainTests {

	private static final String GOOD_CREDENTIALS = "Basic "
			+ Base64.getEncoder().encodeToString("my-client:my-secret".getBytes());

	@LocalServerPort
	int port;

	@Autowired
	MockWebServer mockOAuthServer;

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void exchangedRequestDoesNotOpenTheEndpointOfAPlugin() {
		enqueueToken();

		client().get()
			.uri("/plugin/admin")
			.header(HttpHeaders.AUTHORIZATION, GOOD_CREDENTIALS)
			.exchange()
			.expectStatus()
			.isUnauthorized();
	}

	@Test
	void exchangedRequestIsStillLetThroughTheChainsOfTheApplication() {
		enqueueToken();

		client().get()
			.uri("/api/resource")
			.header(HttpHeaders.AUTHORIZATION, GOOD_CREDENTIALS)
			.exchange()
			.expectStatus()
			.isOk();
	}

	@Test
	void endpointOfAPluginStaysClosedWithoutCredentials() {
		client().get().uri("/plugin/admin").exchange().expectStatus().isUnauthorized();
	}

	private void enqueueToken() {
		String accessToken = new PlainJWT(
				new JWTClaimsSet.Builder().expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1)))).build())
			.serialize();
		this.mockOAuthServer.enqueue(new MockResponse().setResponseCode(200)
			.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.setBody("""
					{"access_token": "%s", "token_type": "Bearer", "expires_in": 3600}
					""".formatted(accessToken)));
	}

	/**
	 * A gateway that secures everything and carries a plugin guarding its own path, at
	 * the order of the last chain a plugin contributes.
	 */
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class Application {

		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE + 450)
		SecurityWebFilterChain pluginChain(ServerHttpSecurity http) {
			http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/plugin/**"));
			http.csrf(ServerHttpSecurity.CsrfSpec::disable);
			http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
			http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
			http.authorizeExchange((spec) -> spec.anyExchange().authenticated());
			return http.build();
		}

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

			@RequestMapping(path = "/plugin/admin", produces = MediaType.APPLICATION_JSON_VALUE)
			Map<String, Object> admin() {
				return Map.of("admin", true);
			}

			@RequestMapping(path = "/api/resource", produces = MediaType.APPLICATION_JSON_VALUE)
			Map<String, Object> resource() {
				return Map.of("resource", true);
			}

		}

	}

}
