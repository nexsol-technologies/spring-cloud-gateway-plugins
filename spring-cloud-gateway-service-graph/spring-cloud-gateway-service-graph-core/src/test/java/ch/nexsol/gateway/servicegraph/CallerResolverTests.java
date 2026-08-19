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

package ch.nexsol.gateway.servicegraph;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.servicegraph.ServiceGraphProperties.Caller;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CallerResolver}.
 */
class CallerResolverTests {

	@Test
	void namesTheCallerAfterTheFirstClaimCarryingAValue() {
		CallerResolver resolver = new CallerResolver(new Caller());

		String caller = resolver.resolve(authenticated(Map.of("client_id", "batch-runner"))).block();

		assertThat(caller).isEqualTo("batch-runner");
	}

	@Test
	void prefersTheClaimsInTheOrderTheyAreConfigured() {
		CallerResolver resolver = new CallerResolver(new Caller());

		String caller = resolver.resolve(authenticated(Map.of("azp", "web-app", "client_id", "batch-runner"))).block();

		assertThat(caller).isEqualTo("web-app");
	}

	@Test
	void fallsBackToTheConfiguredHeaderWhenNoClaimNamedTheCaller() {
		Caller properties = new Caller();
		properties.setHeader("X-Caller");
		CallerResolver resolver = new CallerResolver(properties);

		String caller = resolver.resolve(withHeader("X-Caller", "partner-a")).block();

		assertThat(caller).isEqualTo("partner-a");
	}

	@Test
	void fallsBackToTheHeaderWhenTheTokenCarriesNoneOfTheClaims() {
		Caller properties = new Caller();
		properties.setHeader("X-Caller");
		CallerResolver resolver = new CallerResolver(properties);
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/orders").header("X-Caller", "partner-a"));

		String caller = resolver.resolve(withPrincipal(exchange, token(Map.of("sub", "alice")))).block();

		assertThat(caller).isEqualTo("partner-a");
	}

	@Test
	void readsTheHeaderBeforeTheClaimsWhenTheRelayModeIsOn() {
		Caller properties = new Caller();
		properties.setHeader("X-Caller");
		properties.setHeaderFirst(true);
		CallerResolver resolver = new CallerResolver(properties);
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/orders").header("X-Caller", "service-a"));

		String caller = resolver.resolve(withPrincipal(exchange, token(Map.of("azp", "web-app")))).block();

		assertThat(caller).isEqualTo("service-a");
	}

	@Test
	void fallsBackToTheClaimsWhenTheRelayModeIsOnAndTheHeaderIsAbsent() {
		Caller properties = new Caller();
		properties.setHeader("X-Caller");
		properties.setHeaderFirst(true);
		CallerResolver resolver = new CallerResolver(properties);

		String caller = resolver.resolve(authenticated(Map.of("azp", "web-app"))).block();

		assertThat(caller).isEqualTo("web-app");
	}

	@Test
	void reportsAnUnknownCallerWhenNothingNamesIt() {
		CallerResolver resolver = new CallerResolver(new Caller());

		String caller = resolver.resolve(MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))).block();

		assertThat(caller).isEqualTo(CallerResolver.UNKNOWN);
	}

	@Test
	void ignoresTheHeaderWhenNoneIsConfigured() {
		CallerResolver resolver = new CallerResolver(new Caller());

		String caller = resolver.resolve(withHeader("X-Caller", "partner-a")).block();

		assertThat(caller).isEqualTo(CallerResolver.UNKNOWN);
	}

	@Test
	void countsEveryCallerPastTheMaximumUnderASingleNode() {
		Caller properties = new Caller();
		properties.setClaims(List.of("azp"));
		properties.setMax(2);
		CallerResolver resolver = new CallerResolver(properties);

		assertThat(resolver.resolve(authenticated(Map.of("azp", "first"))).block()).isEqualTo("first");
		assertThat(resolver.resolve(authenticated(Map.of("azp", "second"))).block()).isEqualTo("second");
		assertThat(resolver.resolve(authenticated(Map.of("azp", "third"))).block()).isEqualTo(CallerResolver.OTHER);
	}

	@Test
	void keepsNamingTheCallersItAlreadyKnowsOnceTheMaximumIsReached() {
		Caller properties = new Caller();
		properties.setClaims(List.of("azp"));
		properties.setMax(1);
		CallerResolver resolver = new CallerResolver(properties);
		resolver.resolve(authenticated(Map.of("azp", "first"))).block();

		assertThat(resolver.resolve(authenticated(Map.of("azp", "second"))).block()).isEqualTo(CallerResolver.OTHER);
		assertThat(resolver.resolve(authenticated(Map.of("azp", "first"))).block()).isEqualTo("first");
	}

	private static ServerWebExchange authenticated(Map<String, Object> claims) {
		return withPrincipal(MockServerWebExchange.from(MockServerHttpRequest.get("/orders")), token(claims));
	}

	private static ServerWebExchange withHeader(String name, String value) {
		return MockServerWebExchange.from(MockServerHttpRequest.get("/orders").header(name, value));
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
