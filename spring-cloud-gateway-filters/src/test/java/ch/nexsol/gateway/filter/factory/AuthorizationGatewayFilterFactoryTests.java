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

package ch.nexsol.gateway.filter.factory;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import ch.nexsol.gateway.filter.factory.AuthorizationGatewayFilterFactory.Config;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.NameUtils;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what the authorization filter forwards and what it denies. The filter fails
 * closed: every request it cannot authorize is denied, an unauthenticated one with
 * {@code 401} and an insufficiently authorized one with {@code 403}.
 */
class AuthorizationGatewayFilterFactoryTests {

	private static ValidatorFactory validatorFactory;

	private static Validator validator;

	private final AtomicBoolean forwarded = new AtomicBoolean();

	private final GatewayFilterChain chain = (exchange) -> {
		this.forwarded.set(true);
		return Mono.empty();
	};

	@BeforeAll
	static void setUp() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void tearDown() {
		validatorFactory.close();
	}

	@Test
	void shouldForwardAPrincipalHoldingTheRequiredAuthority() {
		GatewayFilter filter = filterRequiring("READ");

		StepVerifier.create(filter.filter(authenticatedWith("READ"), this.chain)).verifyComplete();
		assertThat(this.forwarded).isTrue();
	}

	@Test
	void shouldForwardAPrincipalHoldingOneOfTheConfiguredAuthorities() {
		GatewayFilter filter = filterRequiring("READ", "WRITE");

		StepVerifier.create(filter.filter(authenticatedWith("WRITE"), this.chain)).verifyComplete();
		assertThat(this.forwarded).isTrue();
	}

	@Test
	void shouldDenyAPrincipalHoldingNoneOfTheConfiguredAuthorities() {
		GatewayFilter filter = filterRequiring("READ", "WRITE");

		expectDenied(filter, authenticatedWith("DELETE"), HttpStatus.FORBIDDEN);
	}

	@Test
	void shouldDenyAPrincipalHoldingNoAuthorityAtAll() {
		GatewayFilter filter = filterRequiring("READ");

		expectDenied(filter, authenticatedWith(), HttpStatus.FORBIDDEN);
	}

	/**
	 * The authority is matched as it is spelled, {@code ROLE_} prefix included: the
	 * filter compares strings and adds no convention of its own.
	 */
	@Test
	void shouldMatchTheAuthorityExactly() {
		GatewayFilter filter = filterRequiring("ROLE_READ");

		expectDenied(filter, authenticatedWith("READ"), HttpStatus.FORBIDDEN);
	}

	/**
	 * The case the fix closed: an exchange carrying no principal used to reach the route
	 * untouched, so the filter let anonymous traffic through whenever no security filter
	 * chain had populated the context.
	 */
	@Test
	void shouldDenyARequestCarryingNoPrincipal() {
		GatewayFilter filter = filterRequiring("READ");

		expectDenied(filter, MockServerWebExchange.from(MockServerHttpRequest.get("https://gateway/api")),
				HttpStatus.UNAUTHORIZED);
	}

	@Test
	void shouldDenyAPrincipalThatIsNotAnAuthentication() {
		GatewayFilter filter = filterRequiring("READ");

		expectDenied(filter, exchangeWith(() -> "plain-principal"), HttpStatus.UNAUTHORIZED);
	}

	/**
	 * An anonymous token is an {@link Authentication}, so it is authorized on its
	 * authorities like any other: it is denied unless {@code ROLE_ANONYMOUS} is among the
	 * configured ones.
	 */
	@Test
	void shouldDenyAnAnonymousPrincipal() {
		GatewayFilter filter = filterRequiring("READ");

		expectDenied(filter, exchangeWith(anonymous()), HttpStatus.FORBIDDEN);
	}

	@Test
	void shouldMapTheShortcutArgumentToTheAuthorities() {
		assertThat(new AuthorizationGatewayFilterFactory().shortcutFieldOrder()).containsExactly("authorities");
	}

	/**
	 * Gathering the shortcut arguments into the list is what keeps
	 * {@code Authorization=READ,WRITE} meaningful: the default shortcut type maps them
	 * positionally, so all but the first would be bound to a field that does not exist
	 * and dropped without a word.
	 */
	@Test
	void shouldGatherEveryShortcutArgumentIntoTheAuthorities() {
		AuthorizationGatewayFilterFactory factory = new AuthorizationGatewayFilterFactory();
		Map<String, String> shortcutArgs = new LinkedHashMap<>();
		shortcutArgs.put(NameUtils.GENERATED_NAME_PREFIX + "0", "READ");
		shortcutArgs.put(NameUtils.GENERATED_NAME_PREFIX + "1", "WRITE");

		Map<String, Object> normalized = factory.shortcutType()
			.normalize(shortcutArgs, factory, new SpelExpressionParser(), new DefaultListableBeanFactory());

		assertThat(normalized).containsOnlyKeys("authorities");
		assertThat(normalized.get("authorities")).asInstanceOf(InstanceOfAssertFactories.LIST)
			.containsExactly("READ", "WRITE");
	}

	@Test
	void shouldRejectAConfigurationWithoutAnyAuthority() {
		Set<ConstraintViolation<Config>> violations = validator.validate(new Config());

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath()).hasToString("authorities"));
	}

	@Test
	void shouldRejectABlankAuthority() {
		Config config = new Config();
		config.setAuthorities(List.of(""));

		Set<ConstraintViolation<Config>> violations = validator.validate(config);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath())
				.hasToString("authorities[0].<list element>"));
	}

	@Test
	void shouldAcceptAConfiguredAuthority() {
		Config config = new Config();
		config.setAuthorities(List.of("READ"));

		assertThat(validator.validate(config)).isEmpty();
	}

	private void expectDenied(GatewayFilter filter, ServerWebExchange exchange, HttpStatusCode expectedStatus) {
		StepVerifier.create(filter.filter(exchange, this.chain))
			.verifyErrorSatisfies(
					(error) -> assertThat(asStatusException(error).getStatusCode()).isEqualTo(expectedStatus));
		assertThat(this.forwarded).isFalse();
	}

	private ResponseStatusException asStatusException(Throwable error) {
		assertThat(error).isInstanceOf(ResponseStatusException.class);
		return (ResponseStatusException) error;
	}

	private GatewayFilter filterRequiring(String... authorities) {
		Config config = new Config();
		config.setAuthorities(List.of(authorities));
		return new AuthorizationGatewayFilterFactory().apply(config);
	}

	private ServerWebExchange authenticatedWith(String... authorities) {
		return exchangeWith(new UsernamePasswordAuthenticationToken("user", "password",
				AuthorityUtils.createAuthorityList(authorities)));
	}

	private ServerWebExchange exchangeWith(Principal principal) {
		return MockServerWebExchange.builder(MockServerHttpRequest.get("https://gateway/api"))
			.principal(principal)
			.build();
	}

	private Authentication anonymous() {
		return new AnonymousAuthenticationToken("key", "anonymousUser",
				AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
	}

}
