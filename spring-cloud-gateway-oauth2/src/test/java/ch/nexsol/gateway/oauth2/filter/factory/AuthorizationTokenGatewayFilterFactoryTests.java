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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.oauth2.filter.factory.AuthorizationTokenGatewayFilterFactory.Config;
import ch.nexsol.gateway.oauth2.filter.factory.AuthorizationTokenGatewayFilterFactory.GrantAccess;
import ch.nexsol.gateway.oauth2.filter.factory.AuthorizationTokenGatewayFilterFactory.MatchMode;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
	void shouldHaveOneOfTheRolesWhenTheMatchIsAny() throws ParseException {
		AuthorizationTokenGatewayFilterFactory factory = new AuthorizationTokenGatewayFilterFactory();
		GrantAccess grantAccess = grantAccess("$.resource_access.*.roles", MatchMode.ANY, "role1", "roleXXX");

		boolean hasAuthority = factory.hasAuthority(claims(), grantAccess);
		assertThat(hasAuthority).isTrue();
	}

	@Test
	void shouldCombineTheRolesAndTheGrantedAccessesWithAllByDefault() {
		assertThat(new GrantAccess().getMatch()).isEqualTo(MatchMode.ALL);
		assertThat(new Config().getGrantAccessesMatch()).isEqualTo(MatchMode.ALL);
	}

	@Test
	void shouldDenyWhenEveryGrantedAccessAndEveryRoleAreRequired() throws ParseException {
		assertThat(hasAuthority(MatchMode.ALL, partiallySatisfied(MatchMode.ALL), neverSatisfied())).isFalse();
	}

	@Test
	void shouldDenyWhenEveryGrantedAccessIsRequiredEvenIfASingleRoleIsEnough() throws ParseException {
		assertThat(hasAuthority(MatchMode.ALL, partiallySatisfied(MatchMode.ANY), neverSatisfied())).isFalse();
	}

	@Test
	void shouldDenyWhenASingleGrantedAccessIsEnoughButNoneIsSatisfied() throws ParseException {
		assertThat(hasAuthority(MatchMode.ANY, partiallySatisfied(MatchMode.ALL), neverSatisfied())).isFalse();
	}

	@Test
	void shouldGrantWhenASingleGrantedAccessIsEnoughAndOneRoleSatisfiesIt() throws ParseException {
		assertThat(hasAuthority(MatchMode.ANY, partiallySatisfied(MatchMode.ANY), neverSatisfied())).isTrue();
	}

	@Test
	void shouldRequireEveryGrantedAccessThroughTheTwoArgumentOverload() throws ParseException {
		AuthorizationTokenGatewayFilterFactory factory = new AuthorizationTokenGatewayFilterFactory();
		List<GrantAccess> grantAccesses = List.of(partiallySatisfied(MatchMode.ANY), neverSatisfied());

		// The overload kept for the callers of the previous signature must behave as ALL.
		assertThat(factory.hasAuthority(claims(), grantAccesses)).isFalse();
	}

	@Test
	void shouldForwardARequestWhenASingleGrantedAccessIsSatisfied() {
		Config config = new Config();
		config.setGrantAccessesMatch(MatchMode.ANY);
		config.setGrantAccesses(List.of(neverSatisfied(), partiallySatisfied(MatchMode.ANY)));
		MockServerWebExchange exchange = authenticated();
		RecordingChain chain = new RecordingChain();

		StepVerifier.create(new AuthorizationTokenGatewayFilterFactory().apply(config).filter(exchange, chain))
			.verifyComplete();
		assertThat(chain.called).isTrue();
	}

	@Test
	void shouldNotCheckTheGrantedAccessesWhenTheListIsEmptyWhateverTheMatchMode() {
		// anyMatch on an empty list is false: the hasCheck() guard is what keeps an empty
		// configuration from denying every request once the match mode is ANY.
		Config config = new Config();
		config.setGrantAccessesMatch(MatchMode.ANY);
		MockServerWebExchange exchange = authenticated();
		RecordingChain chain = new RecordingChain();

		StepVerifier.create(new AuthorizationTokenGatewayFilterFactory().apply(config).filter(exchange, chain))
			.verifyComplete();
		assertThat(chain.called).isTrue();
	}

	@Test
	void shouldDenyABearerHeaderThatSpringSecurityDidNotAuthenticate() {
		// The filter authorizes, it does not authenticate: a token nobody verified is a
		// token anybody can forge, so a raw bearer header counts as no token at all.
		Config config = new Config();
		config.setIssuers(List.of("https://nexsol.tech"));
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/anything").header("Authorization", "Bearer " + token));
		RecordingChain chain = new RecordingChain();

		StepVerifier.create(new AuthorizationTokenGatewayFilterFactory().apply(config).filter(exchange, chain))
			.expectErrorSatisfies((ex) -> assertThat(((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED))
			.verify();
		assertThat(chain.called).isFalse();
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
	void shouldAuthorizeTheTokenSpringSecurityAuthenticated() {
		Config config = new Config();
		config.setIssuers(List.of("https://nexsol.tech"));
		MockServerWebExchange exchange = authenticated();
		RecordingChain chain = new RecordingChain();

		StepVerifier.create(new AuthorizationTokenGatewayFilterFactory().apply(config).filter(exchange, chain))
			.verifyComplete();
		assertThat(chain.called).isTrue();
	}

	@Test
	void shouldRejectABearerWithAForbiddenIssuer() {
		Config config = new Config();
		config.setIssuers(List.of("https://bad.issuer.ch"));
		MockServerWebExchange exchange = authenticated();
		RecordingChain chain = new RecordingChain();

		StepVerifier.create(new AuthorizationTokenGatewayFilterFactory().apply(config).filter(exchange, chain))
			.expectErrorSatisfies(
					(ex) -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
			.verify();
		assertThat(chain.called).isFalse();
	}

	private static Map<String, Object> claims() throws ParseException {
		return JWTParser.parse(token).getJWTClaimsSet().getClaims();
	}

	/**
	 * An exchange whose principal is the {@link JwtAuthenticationToken} a resource server
	 * filter chain would have produced, which is the only shape the filter ever reads.
	 * @return the authenticated exchange
	 */
	private static MockServerWebExchange authenticated() {
		return MockServerWebExchange.builder(MockServerHttpRequest.get("/anything"))
			.principal(new JwtAuthenticationToken(verifiedJwt()))
			.build();
	}

	/**
	 * Builds the {@link Jwt} a resource server would have handed over once it verified
	 * the token: same claims, minus the verification this test has no key to perform.
	 * @return the verified token
	 */
	private static Jwt verifiedJwt() {
		try {
			JWT parsed = JWTParser.parse(token);
			Map<String, Object> claims = new LinkedHashMap<>(parsed.getJWTClaimsSet().getClaims());
			// Jwt keeps its timestamps as Instant where Nimbus hands them over as Date.
			claims.replaceAll((name, value) -> (value instanceof Date date) ? date.toInstant() : value);
			return Jwt.withTokenValue(token)
				.headers((headers) -> headers.putAll(parsed.getHeader().toJSONObject()))
				.claims((all) -> all.putAll(claims))
				.build();
		}
		catch (ParseException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static boolean hasAuthority(MatchMode grantAccessesMatch, GrantAccess... grantAccesses)
			throws ParseException {
		return new AuthorizationTokenGatewayFilterFactory().hasAuthority(claims(), List.of(grantAccesses),
				grantAccessesMatch);
	}

	private static GrantAccess grantAccess(String jsonPath, MatchMode match, String... roles) {
		GrantAccess grantAccess = new GrantAccess();
		grantAccess.setJsonPath(jsonPath);
		grantAccess.setMatch(match);
		grantAccess.setRoles(List.of(roles));
		return grantAccess;
	}

	/**
	 * A requirement the token satisfies partially: the realm holds {@code admin} but not
	 * {@code roleXXX}, so it is satisfied under {@link MatchMode#ANY} only.
	 * @param match how the roles are combined
	 * @return the granted access
	 */
	private static GrantAccess partiallySatisfied(MatchMode match) {
		return grantAccess("$.realm_access.roles", match, "admin", "roleXXX");
	}

	/**
	 * A requirement the token never satisfies, whatever the match mode. The bracket
	 * notation is what addresses a client id holding a hyphen.
	 * @return the granted access
	 */
	private static GrantAccess neverSatisfied() {
		return grantAccess("$.resource_access['spring-client-1'].roles", MatchMode.ALL, "roleYYY");
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
