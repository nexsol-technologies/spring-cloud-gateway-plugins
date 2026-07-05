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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gateway filter factory that authorizes the request by requiring the authenticated
 * principal to hold at least one of the configured authorities, rejecting it with
 * {@code 403 Forbidden} otherwise.
 */
public class AuthorizationGatewayFilterFactory
		extends AbstractGatewayFilterFactory<AuthorizationGatewayFilterFactory.Config> {

	/**
	 * Authorities key.
	 */
	public static final String AUTHORITIES_KEY = "authorities";

	/**
	 * Creates the factory bound to its {@link Config} type.
	 */
	public AuthorizationGatewayFilterFactory() {
		super(AuthorizationGatewayFilterFactory.Config.class);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Maps the single shortcut argument to the {@code authorities} configuration field.
	 */
	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(AUTHORITIES_KEY);
	}

	/**
	 * Builds a filter that forwards the request only when the authenticated principal
	 * holds one of the configured authorities.
	 * @param config the filter configuration holding the required authorities
	 * @return a gateway filter enforcing the authority check
	 */
	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> {
			return exchange.getPrincipal()
				.filter((principal) -> (principal) instanceof Authentication)
				.cast(Authentication.class)
				.map(Authentication::getAuthorities)
				.flatMap((authorities) -> {
					if (!hasAuthority(authorities, config.getAuthorities())) {
						return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
					}
					else {
						return Mono.just(exchange);
					}
				})
				.defaultIfEmpty(exchange)
				.flatMap(chain::filter);
		};
	}

	private boolean hasAuthority(Collection<? extends GrantedAuthority> userAuthorities,
			List<String> authoritiesConfigured) {
		return userAuthorities.stream().anyMatch((userAuth) -> authoritiesConfigured.contains(userAuth.getAuthority()));
	}

	/**
	 * Configuration for {@link AuthorizationGatewayFilterFactory}.
	 */
	@Validated
	public static class Config {

		@NotEmpty
		private List<@NotEmpty String> authorities = new ArrayList<>(0);

		/**
		 * Returns the authorities that grant access to the route.
		 * @return the required authorities
		 */
		public List<String> getAuthorities() {
			return this.authorities;
		}

		/**
		 * Sets the authorities that grant access to the route.
		 * @param authorities the required authorities
		 */
		public void setAuthorities(List<String> authorities) {
			this.authorities = authorities;
		}

	}

}
