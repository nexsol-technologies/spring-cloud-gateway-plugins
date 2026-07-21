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

package ch.nexsol.gateway.routes.security.autoconfigure;

import ch.nexsol.gateway.routes.security.PublicRouteMatcher;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Auto-configuration registering a high-priority {@link SecurityWebFilterChain} that lets
 * requests targeting a route flagged as public bypass authentication (Basic auth, OAuth
 * 2.0).
 * <p>
 * The chain is ordered ahead of the application security chains and only matches public
 * routes (see {@link PublicRouteMatcher}); every other request falls through to the
 * application's own chains unchanged. It can be disabled with
 * {@code spring.cloud.gateway.server.webflux.routes-security.public-routes.enabled=false}.
 */
@AutoConfiguration
@ConditionalOnClass({ SecurityWebFilterChain.class, RouteLocator.class })
@ConditionalOnProperty(prefix = "spring.cloud.gateway.server.webflux.routes-security.public-routes", name = "enabled",
		matchIfMissing = true)
public class RoutesSecurityAutoConfiguration {

	/**
	 * Order of the public-routes security chain. Kept well ahead of the application
	 * chains so a public route is served by it and never reaches the authenticated
	 * chains.
	 */
	public static final int PUBLIC_ROUTES_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

	/**
	 * Registers the security chain matching public routes and permitting them without any
	 * authentication.
	 * @param http the reactive security builder
	 * @param routeLocator the locator used to resolve the targeted route
	 * @return the public-routes security web filter chain
	 */
	@Bean
	@Order(PUBLIC_ROUTES_CHAIN_ORDER)
	SecurityWebFilterChain publicRoutesWebFilterChain(ServerHttpSecurity http, RouteLocator routeLocator) {
		http.securityMatcher(new PublicRouteMatcher(routeLocator));
		http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
		http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
		http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
		http.logout(ServerHttpSecurity.LogoutSpec::disable);
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		return http.build();
	}

}
