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

public class AuthorizationGatewayFilterFactory
		extends AbstractGatewayFilterFactory<AuthorizationGatewayFilterFactory.Config> {

	/**
	 * Authorities key.
	 */
	public static final String AUTHORITIES_KEY = "authorities";

	public AuthorizationGatewayFilterFactory() {
		super(AuthorizationGatewayFilterFactory.Config.class);
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(AUTHORITIES_KEY);
	}

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

	@Validated
	public static class Config {

		@NotEmpty
		private List<@NotEmpty String> authorities = new ArrayList<>(0);

		public List<String> getAuthorities() {
			return this.authorities;
		}

		public void setAuthorities(List<String> authorities) {
			this.authorities = authorities;
		}

	}

}
