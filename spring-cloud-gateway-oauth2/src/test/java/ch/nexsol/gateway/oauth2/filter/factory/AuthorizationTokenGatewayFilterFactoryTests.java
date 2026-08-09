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

package ch.nexsol.gateway.oauth2.filter.factory;

import java.text.ParseException;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.oauth2.filter.factory.AuthorizationTokenGatewayFilterFactory.Config;
import ch.nexsol.gateway.oauth2.filter.factory.AuthorizationTokenGatewayFilterFactory.GrantAccess;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class AuthorizationTokenGatewayFilterFactoryTests {

	private static final String token = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL25leHNvbC50ZWNoIiwic3ViIjoibmV4c29sLWFkbWluIiwiYXVkIjoiYXBpIiwiaWF0IjoxNzA3MDQyOTE3LCJleHAiOjE3MDcwNDM1MTcsImFhYSI6dHJ1ZSwiYXpwIjoibXktY2xpZW50IiwicmVhbG1fYWNjZXNzIjp7InJvbGVzIjpbIm9mZmxpbmVfYWNjZXNzIiwiYWRtaW4iLCJkZWZhdWx0LXJvbGVzLWV4YW1wbGUiLCJ1bWFfYXV0aG9yaXphdGlvbiIsInVzZXIiXX0sInJlc291cmNlX2FjY2VzcyI6eyJzcHJpbmctY2xpZW50LTEiOnsicm9sZXMiOlsicm9sZTEiXX0sInNwcmluZy1jbGllbnQtMiI6eyJyb2xlcyI6WyJyb2xlMiJdfX19.y1lAdAy11VpfUEiKPvij6rb-QeIX_0m7M5rCw8XrT9ZkDkeyPD_uxhgMIvSPvFI_SwT9PYS3wZ1RKSOBezaJresf7JYNBwvI1yHybNvtWRJQeJVLBwuvVlks02AvaXBIdq_d3ZsZBd9x_gzAQ5wCE31eAjb2kgdRFnU3NFvjtkuHDcdZufv_qrJkUIVKNJdPMrttv8_QvnyUE9j_Tjm7KAOBS-_tWaDxDcKB6nJwkmkpu_l2XH9ac1WAb15_orRyGulqsqW1hBWh9vmSvTBFOJQAfPqHXyx-k6oWPjj3regu7nxj8qilpxVa7uWxScuTAYpgd2NbKQJfFqtfQGo5GQ";

	@Test
	void shouldNotHaveNoRole() throws ParseException {
		AuthorizationTokenGatewayFilterFactory factory = new AuthorizationTokenGatewayFilterFactory();
		JWT jwt = JWTParser.parse(token);
		GrantAccess grantAccess = new GrantAccess();
		grantAccess.setJsonPath("$.resource_access.*.roles");
		grantAccess.setRoles(List.of("roleXXX"));

		boolean hasAuthority = factory.hasAuthority(jwt.getJWTClaimsSet().getClaims(), grantAccess);
		assertThat(hasAuthority).isFalse();
	}

	@Test
	void shouldHaveOneRole() throws ParseException {
		AuthorizationTokenGatewayFilterFactory factory = new AuthorizationTokenGatewayFilterFactory();
		JWT jwt = JWTParser.parse(token);
		GrantAccess grantAccess = new GrantAccess();
		grantAccess.setJsonPath("$.resource_access.*.roles");
		grantAccess.setRoles(List.of("role1"));

		boolean hasAuthority = factory.hasAuthority(jwt.getJWTClaimsSet().getClaims(), grantAccess);
		assertThat(hasAuthority).isTrue();
	}

	@Test
	void shouldHaveAllRole() throws ParseException {
		AuthorizationTokenGatewayFilterFactory factory = new AuthorizationTokenGatewayFilterFactory();
		JWT jwt = JWTParser.parse(token);
		GrantAccess grantAccess = new GrantAccess();
		grantAccess.setJsonPath("$.resource_access.*.roles");
		grantAccess.setRoles(List.of("role1", "role2"));

		boolean hasAuthority = factory.hasAuthority(jwt.getJWTClaimsSet().getClaims(), grantAccess);
		assertThat(hasAuthority).isTrue();
	}

	@Test
	void shouldNotHaveOneOfAllRole() throws ParseException {
		AuthorizationTokenGatewayFilterFactory factory = new AuthorizationTokenGatewayFilterFactory();
		JWT jwt = JWTParser.parse(token);
		GrantAccess grantAccess = new GrantAccess();
		grantAccess.setJsonPath("$.resource_access.*.roles");
		grantAccess.setRoles(List.of("role1", "roleXXX"));

		boolean hasAuthority = factory.hasAuthority(jwt.getJWTClaimsSet().getClaims(), grantAccess);
		assertThat(hasAuthority).isFalse();
	}

	@Test
	void shouldRejectRequestWithoutTokenWhenACheckIsConfigured() {
		Config config = new Config();
		config.setIssuers(List.of("https://nexsol.tech"));
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/anything"));
		RecordingChain chain = new RecordingChain();

		StepVerifier.create(new AuthorizationTokenGatewayFilterFactory().apply(config).filter(exchange, chain))
			.expectErrorSatisfies((ex) -> assertThat(((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED))
			.verify();
		assertThat(chain.called).isFalse();
	}

	@Test
	void shouldForwardRequestWithoutTokenWhenNoCheckIsConfigured() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/anything"));
		RecordingChain chain = new RecordingChain();

		StepVerifier.create(new AuthorizationTokenGatewayFilterFactory().apply(new Config()).filter(exchange, chain))
			.verifyComplete();
		assertThat(chain.called).isTrue();
	}

	@Test
	void shouldForwardRequestWithoutTokenOnPublicRoute() {
		Config config = new Config();
		config.setIssuers(List.of("https://nexsol.tech"));
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/anything"));
		exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, publicRoute());
		RecordingChain chain = new RecordingChain();

		StepVerifier.create(new AuthorizationTokenGatewayFilterFactory().apply(config).filter(exchange, chain))
			.verifyComplete();
		assertThat(chain.called).isTrue();
	}

	@Test
	void shouldReadTheAuthorizationHeaderWhateverItsCase() {
		Config config = new Config();
		config.setIssuers(List.of("https://nexsol.tech"));
		// HTTP/2 sends header names lower cased
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/anything").header("authorization", "Bearer " + token));
		RecordingChain chain = new RecordingChain();

		StepVerifier.create(new AuthorizationTokenGatewayFilterFactory().apply(config).filter(exchange, chain))
			.verifyComplete();
		assertThat(chain.called).isTrue();
	}

	@Test
	void shouldRejectABearerWithAForbiddenIssuer() {
		Config config = new Config();
		config.setIssuers(List.of("https://bad.issuer.ch"));
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/anything").header("Authorization", "Bearer " + token));
		RecordingChain chain = new RecordingChain();

		StepVerifier.create(new AuthorizationTokenGatewayFilterFactory().apply(config).filter(exchange, chain))
			.expectErrorSatisfies(
					(ex) -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
			.verify();
		assertThat(chain.called).isFalse();
	}

	private static Route publicRoute() {
		return Route.async()
			.id("public-route")
			.uri("http://localhost")
			.predicate((exchange) -> true)
			.metadata(Map.of("public", true))
			.build();
	}

	/**
	 * Filter chain recording whether the request was forwarded downstream.
	 */
	private static final class RecordingChain implements GatewayFilterChain {

		private boolean called;

		@Override
		public Mono<Void> filter(ServerWebExchange exchange) {
			this.called = true;
			return Mono.empty();
		}

	}

}
