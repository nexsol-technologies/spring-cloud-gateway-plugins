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

import reactor.core.publisher.Mono;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

/**
 * Whether the principal behind a request may change a logging level, by the role it
 * holds.
 * <p>
 * Changing a level rewrites what a running gateway records, on whichever instance was
 * selected, so it is the one introspection action that is not simply reading. It asks for
 * three things in turn: that the operator allowed the write at all, that there is an
 * authenticated principal, and that this principal holds the configured role.
 * <p>
 * An anonymous request never passes, whatever the role says. Spring Security represents
 * an unauthenticated caller with an {@code AnonymousAuthenticationToken} that is
 * {@code isAuthenticated() == true}, so the token type is what decides here and not that
 * flag &mdash; reading it alone is how an anonymous caller ends up treated as signed in.
 */
public class RoleLoggerWriteAccess implements LoggerWriteAccess {

	private static final String ROLE_PREFIX = "ROLE_";

	private static final String ANONYMOUS = "org.springframework.security.authentication.AnonymousAuthenticationToken";

	private final InsightsProperties properties;

	/**
	 * Creates the check over the introspection configuration.
	 * @param properties the introspection configuration
	 */
	public RoleLoggerWriteAccess(InsightsProperties properties) {
		this.properties = properties;
	}

	/**
	 * Whether the principal behind this request may change a level.
	 * @param exchange the request being served
	 * @return {@code true} when the write is allowed
	 */
	@Override
	public Mono<Boolean> granted(ServerWebExchange exchange) {
		if (!this.properties.isLoggersWritable()) {
			return Mono.just(false);
		}
		return exchange.<Principal>getPrincipal().map(this::holdsTheRole).defaultIfEmpty(false);
	}

	private boolean holdsTheRole(Principal principal) {
		if (!(principal instanceof Authentication authentication) || !authentication.isAuthenticated()
				|| isAnonymous(authentication)) {
			return false;
		}
		String required = this.properties.getLoggersRole();
		if (!StringUtils.hasText(required)) {
			// No role asked for: being authenticated is the whole requirement.
			return true;
		}
		String wanted = required.startsWith(ROLE_PREFIX) ? required : ROLE_PREFIX + required;
		for (GrantedAuthority authority : authentication.getAuthorities()) {
			String held = authority.getAuthority();
			if (wanted.equals(held) || required.equals(held)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Named rather than imported: the anonymous token lives in a package this module does
	 * not otherwise need, and matching the type by name keeps that dependency out.
	 */
	private static boolean isAnonymous(Authentication authentication) {
		return ANONYMOUS.equals(authentication.getClass().getName());
	}

}
