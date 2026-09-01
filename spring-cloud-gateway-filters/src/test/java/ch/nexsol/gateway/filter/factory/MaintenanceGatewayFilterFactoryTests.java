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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import ch.nexsol.gateway.filter.factory.MaintenanceGatewayFilterFactory.AllowedClaim;
import ch.nexsol.gateway.filter.factory.MaintenanceGatewayFilterFactory.Config;
import ch.nexsol.gateway.filter.factory.MaintenanceGatewayFilterFactory.MatchMode;
import com.jayway.jsonpath.InvalidPathException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Verifies what the maintenance filter closes, what it lets through and what it answers.
 * The route is only ever forwarded when the window is shut or the caller belongs to the
 * exempted population; every other request is answered by the gateway itself.
 */
class MaintenanceGatewayFilterFactoryTests {

	private static final Instant NOW = Instant.parse("2025-09-01T23:00:00Z");

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
	void shouldCloseTheRouteWhenNoWindowIsConfigured() {
		MockServerWebExchange exchange = anonymousExchange();

		StepVerifier.create(filter(new Config()).filter(exchange, this.chain)).verifyComplete();

		assertThat(this.forwarded).isFalse();
		assertThat(exchange.getResponse().getStatusCode())
			.isEqualTo(HttpStatusCode.valueOf(MaintenanceGatewayFilterFactory.DEFAULT_STATUS));
		assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(bodyOf(exchange)).isEqualTo("{\"message\":\"" + MaintenanceGatewayFilterFactory.DEFAULT_MESSAGE
				+ "\",\"start\":null,\"end\":null}");
	}

	/**
	 * The body is what a front end has to display, so it carries the message and both
	 * bounds of the window, rendered as the operator wrote them, offset included.
	 */
	@Test
	void shouldCarryTheMessageAndTheWindowInTheBody() {
		Config config = new Config();
		config.setMessage("Nous revenons à 4h.");
		config.setStart("2025-09-02T00:00:00+02:00");
		config.setEnd("2025-09-02T04:00:00+02:00");

		MockServerWebExchange exchange = anonymousExchange();
		StepVerifier.create(filter(config).filter(exchange, this.chain)).verifyComplete();

		assertThat(bodyOf(exchange)).isEqualTo("{\"message\":\"Nous revenons à 4h.\","
				+ "\"start\":\"2025-09-02T00:00:00+02:00\",\"end\":\"2025-09-02T04:00:00+02:00\"}");
	}

	/**
	 * The end of the window is exclusive, so it is the first moment a retry can succeed
	 * &mdash; which is exactly what {@code Retry-After} names.
	 */
	@Test
	void shouldPointTheCallerAtTheEndOfTheWindow() {
		Config config = new Config();
		config.setEnd("2025-09-02T04:00:00+02:00");

		MockServerWebExchange exchange = anonymousExchange();
		StepVerifier.create(filter(config).filter(exchange, this.chain)).verifyComplete();

		assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
			.isEqualTo("Tue, 02 Sep 2025 02:00:00 GMT");
	}

	@Test
	void shouldPromiseNoReturnWhenTheWindowHasNoEnd() {
		MockServerWebExchange exchange = anonymousExchange();
		StepVerifier.create(filter(new Config()).filter(exchange, this.chain)).verifyComplete();

		assertThat(exchange.getResponse().getHeaders().headerNames()).doesNotContain(HttpHeaders.RETRY_AFTER);
	}

	@Test
	void shouldAnswerTheConfiguredStatus() {
		Config config = new Config();
		config.setStatus(503);

		MockServerWebExchange exchange = anonymousExchange();
		StepVerifier.create(filter(config).filter(exchange, this.chain)).verifyComplete();

		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatusCode.valueOf(503));
	}

	@Test
	void shouldForwardBeforeTheWindowOpens() {
		Config config = new Config();
		config.setStart("2025-09-02T00:00:00Z");

		expectForwarded(config, anonymousExchange());
	}

	/**
	 * The start is inclusive: the very second the window opens, the route is closed.
	 */
	@Test
	void shouldCloseAtTheVerySecondTheWindowOpens() {
		Config config = new Config();
		config.setStart("2025-09-01T23:00:00Z");

		expectClosed(config, anonymousExchange());
	}

	/**
	 * The end is exclusive: the very second the window closes, the route is served again.
	 */
	@Test
	void shouldForwardAtTheVerySecondTheWindowCloses() {
		Config config = new Config();
		config.setStart("2025-09-01T22:00:00Z");
		config.setEnd("2025-09-01T23:00:00Z");

		expectForwarded(config, anonymousExchange());
	}

	@Test
	void shouldCloseInsideTheWindow() {
		Config config = new Config();
		config.setStart("2025-09-01T22:00:00Z");
		config.setEnd("2025-09-02T02:00:00Z");

		expectClosed(config, anonymousExchange());
	}

	/**
	 * The bounds are compared as instants: a window opening at 00:30 in {@code +02:00} is
	 * already open at 23:00 UTC, where reading the two wall clocks against each other
	 * would have held it an hour and a half away.
	 */
	@Test
	void shouldCompareTheWindowAcrossOffsets() {
		Config config = new Config();
		config.setStart("2025-09-02T00:30:00+02:00");

		expectClosed(config, anonymousExchange());
	}

	@Test
	void shouldForwardAPrincipalHoldingAnExemptedAuthority() {
		Config config = new Config();
		config.setAllowedAuthorities(List.of("ROLE_ADMIN"));

		expectForwarded(config, exchangeWith(new UsernamePasswordAuthenticationToken("admin", "password",
				AuthorityUtils.createAuthorityList("ROLE_ADMIN"))));
	}

	@Test
	void shouldCloseForAPrincipalHoldingNoneOfTheExemptedAuthorities() {
		Config config = new Config();
		config.setAllowedAuthorities(List.of("ROLE_ADMIN"));

		expectClosed(config, exchangeWith(new UsernamePasswordAuthenticationToken("user", "password",
				AuthorityUtils.createAuthorityList("ROLE_USER"))));
	}

	@Test
	void shouldCloseForARequestCarryingNoPrincipalWhenAnExemptionIsConfigured() {
		Config config = new Config();
		config.setAllowedAuthorities(List.of("ROLE_ADMIN"));

		expectClosed(config, anonymousExchange());
	}

	@Test
	void shouldForwardAJwtWhoseClaimHoldsAnExemptedValue() {
		Config config = new Config();
		config.setAllowedClaims(List.of(claim("$.resource_access.*.roles", MatchMode.ANY, "maintenance-bypass")));

		expectForwarded(config, exchangeWith(jwt(Map.of("resource_access",
				Map.of("gateway", Map.of("roles", List.of("reader", "maintenance-bypass")))))));
	}

	@Test
	void shouldForwardAJwtWhoseClaimIsACommaSeparatedList() {
		Config config = new Config();
		config.setAllowedClaims(List.of(claim("$.scope", MatchMode.ANY, "maintenance-bypass")));

		expectForwarded(config, exchangeWith(jwt(Map.of("scope", "openid,maintenance-bypass"))));
	}

	/**
	 * {@code scope} is space separated by RFC 6749, so a route keyed on it would lock the
	 * exempted population out if the claim were only ever split on commas.
	 */
	@Test
	void shouldForwardAJwtWhoseScopeIsSpaceSeparated() {
		Config config = new Config();
		config.setAllowedClaims(List.of(claim("$.scope", MatchMode.ANY, "maintenance-bypass")));

		expectForwarded(config, exchangeWith(jwt(Map.of("scope", "openid profile maintenance-bypass"))));
	}

	/**
	 * The claim is offered whole as well as split, so a single value carrying a space of
	 * its own is not shredded into two that match nothing.
	 */
	@Test
	void shouldForwardAJwtWhoseClaimIsOneValueCarryingASpace() {
		Config config = new Config();
		config.setAllowedClaims(List.of(claim("$.department", MatchMode.ANY, "Power User")));

		expectForwarded(config, exchangeWith(jwt(Map.of("department", "Power User"))));
	}

	/**
	 * A claim carrying a flag rather than a list of roles is compared as it prints, so
	 * {@code maintenance_bypass: true} is matched by the value {@code true}.
	 */
	@Test
	void shouldForwardAJwtWhoseScalarClaimPrintsAnExemptedValue() {
		Config config = new Config();
		config.setAllowedClaims(List.of(claim("$.maintenance_bypass", MatchMode.ANY, "true")));

		expectForwarded(config, exchangeWith(jwt(Map.of("maintenance_bypass", Boolean.TRUE))));
	}

	@Test
	void shouldCloseWhenTheClaimPathResolvesToNothing() {
		Config config = new Config();
		config.setAllowedClaims(List.of(claim("$.resource_access.*.roles", MatchMode.ANY, "maintenance-bypass")));

		expectClosed(config, exchangeWith(jwt(Map.of("scope", "openid"))));
	}

	@Test
	void shouldCloseWhenTheClaimHoldsNoneOfTheExemptedValues() {
		Config config = new Config();
		config.setAllowedClaims(List.of(claim("$.realm_access.roles", MatchMode.ANY, "maintenance-bypass")));

		expectClosed(config, exchangeWith(jwt(Map.of("realm_access", Map.of("roles", List.of("reader"))))));
	}

	/**
	 * A principal that is not an OAuth2 token has no claims to read, so a route exempting
	 * on claims alone stays closed to it.
	 */
	@Test
	void shouldCloseForAPrincipalCarryingNoToken() {
		Config config = new Config();
		config.setAllowedClaims(List.of(claim("$.scope", MatchMode.ANY, "maintenance-bypass")));

		expectClosed(config, exchangeWith(new UsernamePasswordAuthenticationToken("user", "password",
				AuthorityUtils.createAuthorityList("ROLE_USER"))));
	}

	@Test
	void shouldRequireASingleClaimByDefault() {
		Config config = new Config();
		config.setAllowedClaims(List.of(claim("$.scope", MatchMode.ANY, "maintenance-bypass"),
				claim("$.realm_access.roles", MatchMode.ANY, "operator")));

		expectForwarded(config, exchangeWith(jwt(Map.of("scope", "openid,maintenance-bypass"))));
	}

	@Test
	void shouldRequireEveryClaimWhenTheMatchModeIsAll() {
		Config config = new Config();
		config.setAllowedClaimsMatch(MatchMode.ALL);
		config.setAllowedClaims(List.of(claim("$.scope", MatchMode.ANY, "maintenance-bypass"),
				claim("$.realm_access.roles", MatchMode.ANY, "operator")));

		expectClosed(config, exchangeWith(jwt(Map.of("scope", "openid,maintenance-bypass"))));
		expectForwarded(config, exchangeWith(jwt(
				Map.of("scope", "openid,maintenance-bypass", "realm_access", Map.of("roles", List.of("operator"))))));
	}

	@Test
	void shouldRequireEveryValueOfAClaimWhenItsMatchModeIsAll() {
		Config config = new Config();
		config
			.setAllowedClaims(List.of(claim("$.realm_access.roles", MatchMode.ALL, "maintenance-bypass", "operator")));

		expectClosed(config, exchangeWith(jwt(Map.of("realm_access", Map.of("roles", List.of("operator"))))));
		expectForwarded(config,
				exchangeWith(jwt(Map.of("realm_access", Map.of("roles", List.of("operator", "maintenance-bypass"))))));
	}

	/**
	 * The trap {@code allMatch} sets on an empty stream: a route exempting on authorities
	 * only, with the claims combined by {@code ALL}, would otherwise let every caller
	 * carrying a token through.
	 */
	@Test
	void shouldNotExemptOnAnEmptyClaimListInAllMode() {
		Config config = new Config();
		config.setAllowedAuthorities(List.of("ROLE_ADMIN"));
		config.setAllowedClaimsMatch(MatchMode.ALL);

		expectClosed(config, exchangeWith(jwt(Map.of("scope", "openid"))));
	}

	@Test
	void shouldAcceptADateAndTimeCarryingItsOffset() {
		Config config = new Config();
		config.setStart("2025-09-02T00:00:00+02:00");

		assertThat(config.getStart().toInstant()).isEqualTo(Instant.parse("2025-09-01T22:00:00Z"));
	}

	@Test
	void shouldLeaveTheBoundUnsetWhenItIsBlank() {
		Config config = new Config();
		config.setStart("");
		config.setEnd(null);

		assertThat(config.getStart()).isNull();
		assertThat(config.getEnd()).isNull();
	}

	@Test
	void shouldRejectADateAndTimeWithoutAnOffset() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Config().setStart("2025-09-02T00:00:00"))
			.withMessageContaining("is not a valid 'start'");
	}

	/**
	 * The path is compiled while the route is built, so a typo fails the configuration
	 * rather than throwing once per request from inside the filter &mdash; which would
	 * close the route to the very population it exempts.
	 */
	@Test
	void shouldRejectAMalformedJsonPath() {
		assertThatExceptionOfType(InvalidPathException.class).isThrownBy(() -> new AllowedClaim().setJsonPath("$.["));
	}

	@Test
	void shouldRejectAWindowEndingBeforeItStarts() {
		Config config = new Config();
		config.setStart("2025-09-02T04:00:00Z");
		config.setEnd("2025-09-02T00:00:00Z");

		Set<ConstraintViolation<Config>> violations = validator.validate(config);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath()).hasToString("windowOrdered"));
	}

	@Test
	void shouldRejectAStatusOutsideTheErrorRange() {
		Config config = new Config();
		config.setStatus(200);

		Set<ConstraintViolation<Config>> violations = validator.validate(config);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath()).hasToString("status"));
	}

	@Test
	void shouldRejectAClaimWithoutAnyValue() {
		Config config = new Config();
		AllowedClaim claim = new AllowedClaim();
		claim.setJsonPath("$.scope");
		config.setAllowedClaims(List.of(claim));

		Set<ConstraintViolation<Config>> violations = validator.validate(config);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath()).hasToString("allowedClaims[0].values"));
	}

	@Test
	void shouldAcceptTheDefaultConfiguration() {
		assertThat(validator.validate(new Config())).isEmpty();
	}

	/**
	 * The filter declares no shortcut field: a message is free text and would be cut at
	 * its first comma, so the arguments are always written out.
	 */
	@Test
	void shouldDeclareNoShortcutArgument() {
		assertThat(new MaintenanceGatewayFilterFactory().shortcutFieldOrder()).isEmpty();
	}

	private void expectForwarded(Config config, MockServerWebExchange exchange) {
		this.forwarded.set(false);
		StepVerifier.create(filter(config).filter(exchange, this.chain)).verifyComplete();

		assertThat(this.forwarded).isTrue();
		assertThat(exchange.getResponse().getStatusCode()).isNull();
	}

	private void expectClosed(Config config, MockServerWebExchange exchange) {
		this.forwarded.set(false);
		StepVerifier.create(filter(config).filter(exchange, this.chain)).verifyComplete();

		assertThat(this.forwarded).isFalse();
		assertThat(exchange.getResponse().getStatusCode())
			.isEqualTo(HttpStatusCode.valueOf(MaintenanceGatewayFilterFactory.DEFAULT_STATUS));
	}

	private GatewayFilter filter(Config config) {
		return new MaintenanceGatewayFilterFactory(Clock.fixed(NOW, ZoneOffset.UTC)).apply(config);
	}

	private static AllowedClaim claim(String jsonPath, MatchMode match, String... values) {
		AllowedClaim claim = new AllowedClaim();
		claim.setJsonPath(jsonPath);
		claim.setMatch(match);
		claim.setValues(List.of(values));
		return claim;
	}

	private static JwtAuthenticationToken jwt(Map<String, Object> claims) {
		Jwt token = Jwt.withTokenValue("token").header("alg", "none").claims((all) -> all.putAll(claims)).build();
		return new JwtAuthenticationToken(token, AuthorityUtils.createAuthorityList("ROLE_USER"));
	}

	private static MockServerWebExchange anonymousExchange() {
		return MockServerWebExchange.from(MockServerHttpRequest.get("https://gateway/api"));
	}

	private static MockServerWebExchange exchangeWith(Principal principal) {
		return MockServerWebExchange.builder(MockServerHttpRequest.get("https://gateway/api"))
			.principal(principal)
			.build();
	}

	private static String bodyOf(ServerWebExchange exchange) {
		return ((MockServerWebExchange) exchange).getResponse().getBodyAsString().block();
	}

}
