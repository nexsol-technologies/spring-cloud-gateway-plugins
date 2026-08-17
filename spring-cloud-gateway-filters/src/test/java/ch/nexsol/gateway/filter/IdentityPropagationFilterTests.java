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

package ch.nexsol.gateway.filter;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link IdentityPropagationFilter}.
 */
class IdentityPropagationFilterTests {

	@Test
	void writesTheIdentityOfTheCallerOnTheForwardedRequest() {
		HttpHeaders forwarded = forward(filter(),
				authenticated(Map.of("iss", "https://issuer.example", "azp", "web-app", "preferred_username", "alice"),
						MockServerHttpRequest.get("/orders")));

		assertThat(forwarded.getFirst("x-issuerid")).isEqualTo("https://issuer.example");
		assertThat(forwarded.getFirst("x-clientid")).isEqualTo("web-app");
		assertThat(forwarded.getFirst("x-userid")).isEqualTo("alice");
	}

	@Test
	void startsTheChainWithTheIdentityOfTheFirstCaller() {
		HttpHeaders forwarded = forward(filter(),
				authenticated(Map.of("azp", "web-app", "sub", "alice"), MockServerHttpRequest.get("/orders")));

		assertThat(forwarded.getFirst("x-origin-clientid")).isEqualTo("web-app");
		assertThat(forwarded.getFirst("x-origin-userid")).isEqualTo("alice");
	}

	@Test
	void keepsTheOriginOfTheChainWhenAnInternalServiceCarriesIt() {
		MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/billing")
			.header("x-origin-clientid", "web-app")
			.header("x-origin-userid", "alice");

		HttpHeaders forwarded = forward(filter("service-a"),
				authenticated(Map.of("azp", "service-a", "sub", "service-a"), request));

		assertThat(forwarded.getFirst("x-clientid")).isEqualTo("service-a");
		assertThat(forwarded.getFirst("x-origin-clientid")).isEqualTo("web-app");
		assertThat(forwarded.getFirst("x-origin-userid")).isEqualTo("alice");
	}

	@Test
	void replacesAnOriginForgedByAnOutsideCaller() {
		MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/orders")
			.header("x-origin-clientid", "service-a")
			.header("x-origin-userid", "admin");

		HttpHeaders forwarded = forward(filter("service-a"),
				authenticated(Map.of("azp", "web-app", "sub", "alice"), request));

		assertThat(forwarded.getFirst("x-origin-clientid")).isEqualTo("web-app");
		assertThat(forwarded.getFirst("x-origin-userid")).isEqualTo("alice");
	}

	@Test
	void takesTheOriginOfAnInternalServiceThatCarriedNone() {
		HttpHeaders forwarded = forward(filter("service-a"),
				authenticated(Map.of("azp", "service-a", "sub", "service-a"), MockServerHttpRequest.get("/billing")));

		assertThat(forwarded.getFirst("x-origin-clientid")).isEqualTo("service-a");
	}

	@Test
	void believesNobodyWhenNoInternalClientIsDeclared() {
		MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/billing")
			.header("x-origin-clientid", "web-app");

		HttpHeaders forwarded = forward(filter(),
				authenticated(Map.of("azp", "service-a", "sub", "service-a"), request));

		assertThat(forwarded.getFirst("x-origin-clientid")).isEqualTo("service-a");
	}

	@Test
	void stripsTheHeadersOfARequestCarryingNoToken() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders")
			.header("x-clientid", "service-a")
			.header("x-userid", "admin")
			.header("x-origin-clientid", "service-a")
			.header("x-origin-issuerid", "https://issuer.example"));

		HttpHeaders forwarded = forward(filter("service-a"), exchange);

		assertThat(forwarded.headerNames()).doesNotContain("x-clientid", "x-userid", "x-origin-clientid",
				"x-origin-issuerid");
	}

	@Test
	void leavesOutWhatTheTokenDoesNotSay() {
		HttpHeaders forwarded = forward(filter(),
				authenticated(Map.of("sub", "alice"), MockServerHttpRequest.get("/orders")));

		assertThat(forwarded.getFirst("x-userid")).isEqualTo("alice");
		assertThat(forwarded.headerNames()).doesNotContain("x-clientid", "x-issuerid");
	}

	@Test
	void writesTheConfiguredHeaderNames() {
		IdentityPropagationProperties properties = new IdentityPropagationProperties();
		properties.getCurrent().setClient("X-Consumer");

		HttpHeaders forwarded = forward(new IdentityPropagationFilter(properties),
				authenticated(Map.of("azp", "web-app"), MockServerHttpRequest.get("/orders")));

		assertThat(forwarded.getFirst("X-Consumer")).isEqualTo("web-app");
	}

	private static IdentityPropagationFilter filter(String... internalClients) {
		IdentityPropagationProperties properties = new IdentityPropagationProperties();
		properties.setInternalClients(List.of(internalClients));
		return new IdentityPropagationFilter(properties);
	}

	/**
	 * Runs the filter and returns the headers of the request it handed to the chain,
	 * which is what the gateway forwards upstream.
	 */
	private static HttpHeaders forward(IdentityPropagationFilter filter, ServerWebExchange exchange) {
		AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();
		GatewayFilterChain chain = (forwardedExchange) -> {
			forwarded.set(forwardedExchange.getRequest().getHeaders());
			return Mono.empty();
		};
		filter.filter(exchange, chain).block();
		return forwarded.get();
	}

	private static ServerWebExchange authenticated(Map<String, Object> claims,
			MockServerHttpRequest.BaseBuilder<?> request) {
		return withPrincipal(MockServerWebExchange.from(request.build()), token(claims));
	}

	private static JwtAuthenticationToken token(Map<String, Object> claims) {
		Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none");
		claims.forEach(builder::claim);
		return new JwtAuthenticationToken(builder.build());
	}

	private static ServerWebExchange withPrincipal(ServerWebExchange exchange, Principal principal) {
		return new ServerWebExchangeDecorator(exchange) {
			@SuppressWarnings("unchecked")
			@Override
			public <T extends Principal> Mono<T> getPrincipal() {
				return (Mono<T>) Mono.just(principal);
			}
		};
	}

}
