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

package ch.nexsol.gateway.ui.insights;

import java.security.Principal;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RoleLoggerWriteAccess}.
 */
class RoleLoggerWriteAccessTests {

	@Test
	void letsThroughAPrincipalHoldingTheConfiguredRole() {
		assertThat(granted(properties("ADMIN", true), user("ROLE_ADMIN"))).isTrue();
	}

	/**
	 * A provider may or may not already carry the prefix, so the role is matched with it
	 * and without it rather than one form being assumed.
	 */
	@Test
	void matchesTheRoleWithOrWithoutItsPrefix() {
		assertThat(granted(properties("ADMIN", true), user("ADMIN"))).isTrue();
		assertThat(granted(properties("ROLE_ADMIN", true), user("ROLE_ADMIN"))).isTrue();
	}

	@Test
	void turnsAwayAPrincipalWithoutTheRole() {
		assertThat(granted(properties("ADMIN", true), user("ROLE_OPERATOR"))).isFalse();
	}

	@Test
	void turnsAwayARequestCarryingNoPrincipal() {
		assertThat(granted(properties("ADMIN", true), null)).isFalse();
	}

	/**
	 * Spring Security hands an unauthenticated caller an anonymous token whose
	 * {@code isAuthenticated()} is {@code true}. Reading that flag alone is how an
	 * anonymous caller ends up treated as signed in.
	 */
	@Test
	void turnsAwayAnAnonymousPrincipalEvenWhenItCarriesTheRole() {
		Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
				AuthorityUtils.createAuthorityList("ROLE_ADMIN"));

		assertThat(granted(properties("ADMIN", true), anonymous)).isFalse();
	}

	@Test
	void letsThroughAnyAuthenticatedPrincipalWhenNoRoleIsAskedFor() {
		assertThat(granted(properties("", true), user("ROLE_ANYTHING"))).isTrue();
		assertThat(granted(properties(null, true), user("ROLE_ANYTHING"))).isTrue();
	}

	/**
	 * The switch sits above the role: turned off, no principal changes a level however
	 * privileged.
	 */
	@Test
	void turnsAwayEveryoneWhenTheWriteIsSwitchedOff() {
		assertThat(granted(properties("ADMIN", false), user("ROLE_ADMIN"))).isFalse();
	}

	private static boolean granted(InsightsProperties properties, Authentication authentication) {
		ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/ui/insights/loggers"));
		ServerWebExchange carrying = (authentication != null) ? new PrincipalExchange(exchange, authentication)
				: exchange;
		return Boolean.TRUE.equals(new RoleLoggerWriteAccess(properties).granted(carrying).block());
	}

	private static InsightsProperties properties(String role, boolean writable) {
		InsightsProperties properties = new InsightsProperties();
		properties.setLoggersRole(role);
		properties.setLoggersWritable(writable);
		return properties;
	}

	private static Authentication user(String authority) {
		return new UsernamePasswordAuthenticationToken("operator", "n/a",
				AuthorityUtils.createAuthorityList(authority));
	}

	/**
	 * A mock exchange answers no principal, so the one under test is put on a decorator
	 * rather than into a security context the check does not read.
	 */
	private static final class PrincipalExchange extends ServerWebExchangeDecorator {

		private final Authentication authentication;

		PrincipalExchange(ServerWebExchange delegate, Authentication authentication) {
			super(delegate);
			this.authentication = authentication;
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T extends Principal> Mono<T> getPrincipal() {
			return Mono.just((T) this.authentication);
		}

	}

}
