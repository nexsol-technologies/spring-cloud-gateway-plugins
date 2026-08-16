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

package ch.nexsol.gateway.database.autoconfigure;

import ch.nexsol.gateway.database.RouteManagementPaths;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Auto-configuration contributing the security filter chain of the route management:
 * creating, changing and deleting a route asks for an authenticated principal, without
 * the application having to write a rule for it.
 * <p>
 * The chain is ordered behind the one the console contributes, so a gateway carrying the
 * console keeps a single place deciding who reaches what and this chain never sees those
 * paths. It is what closes them on a gateway assembled without the console.
 * <p>
 * Two conditions are worth reading twice, because they are what keeps a plugin from
 * making a gateway <em>less</em> safe by being added to it:
 * <ul>
 * <li>The chain is only contributed when another {@link SecurityWebFilterChain} already
 * exists. Spring Security serves its own "everything authenticated" chain as long as
 * there is none, and that chain already covers these paths; replacing it with one that
 * matches the route management alone would leave every other path of the application
 * unguarded. With one already declared, that default is gone and the gap is real, which
 * is when this chain fills it.</li>
 * <li>It is only contributed when the application has something to authenticate against
 * &mdash; a user directory, an authentication manager, or an issuer whose tokens it can
 * validate. Closing a door with no key behind it would leave no way through it at all,
 * and turn adding this plugin into an outage. A gateway with no authentication at all
 * closes these endpoints with
 * {@code spring.cloud.gateway.server.webflux.routes-database.access} instead, which asks
 * nothing of anybody.</li>
 * </ul>
 * <p>
 * The chain can be turned off with
 * {@code spring.cloud.gateway.server.webflux.routes-database.security-chain-enabled=false}
 * or replaced by declaring a bean named {@code routeManagementSecurityWebFilterChain}.
 */
@AutoConfiguration(after = GatewayDatabaseAutoConfiguration.class,
		afterName = {
				"org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration",
				"ch.nexsol.gateway.ui.autoconfigure.GatewayUiSecurityAutoConfiguration",
				"ch.nexsol.gateway.openapi.autoconfigure.HubApiSecurityAutoConfiguration",
				"ch.nexsol.gateway.routes.security.autoconfigure.RoutesSecurityAutoConfiguration",
				"ch.nexsol.gateway.oauth2.autoconfigure.BasicAuthExchangeSecurityAutoConfiguration" })
@ConditionalOnClass({ SecurityWebFilterChain.class, ServerHttpSecurity.class })
@Conditional(OnRouteManagementExposedCondition.class)
@ConditionalOnProperty(prefix = "spring.cloud.gateway.server.webflux.routes-database", name = "security-chain-enabled",
		matchIfMissing = true)
public class GatewayDatabaseSecurityAutoConfiguration {

	/**
	 * Order of the contributed chain: behind the console, which governs these paths when
	 * it is there, and ahead of the chains an application usually declares from
	 * {@code 1}.
	 */
	public static final int ROUTE_MANAGEMENT_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 350;

	/**
	 * Registers the chain asking for a principal on the paths that change the routing
	 * table.
	 * @param http the reactive security builder
	 * @param userDetailsService the user directory of the application, if it declared one
	 * @param authenticationManager the authentication manager of the application, if it
	 * declared one
	 * @param jwtDecoder the decoder validating Bearer tokens, if the application
	 * configured an issuer
	 * @return the route management security filter chain
	 */
	@Bean
	@Order(ROUTE_MANAGEMENT_CHAIN_ORDER)
	// The declaration of the API rather than the plugin itself: an application that
	// switched the whole auto-configuration off serves none of these paths, and closing
	// what nobody answers would turn a 404 into a 401.
	@ConditionalOnBean(value = SecurityWebFilterChain.class, name = "routeApiSecuredPaths")
	@Conditional(AnyAuthenticationAvailable.class)
	@ConditionalOnMissingBean(name = "routeManagementSecurityWebFilterChain")
	SecurityWebFilterChain routeManagementSecurityWebFilterChain(ServerHttpSecurity http,
			ObjectProvider<ReactiveUserDetailsService> userDetailsService,
			ObjectProvider<ReactiveAuthenticationManager> authenticationManager,
			ObjectProvider<ReactiveJwtDecoder> jwtDecoder) {
		http.cors(withDefaults());
		http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(RouteManagementPaths.API.toArray(String[]::new)));
		http.authorizeExchange((spec) -> spec.anyExchange().authenticated());
		// Every path this chain matches is called with a token and a JSON body, by a
		// client
		// holding no session and therefore no CSRF token to send back. The page over the
		// same routes is a view of the console, which keeps the protection its forms
		// carry.
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		// Only the mechanisms the application can actually honour: configuring Basic
		// without an authentication manager to check the credentials against leaves
		// Spring Security unable to build the filter, and the application does not start.
		if (userDetailsService.getIfAvailable() != null || authenticationManager.getIfAvailable() != null) {
			http.httpBasic(withDefaults());
		}
		if (jwtDecoder.getIfAvailable() != null) {
			http.oauth2ResourceServer((oauth2) -> oauth2.jwt(withDefaults()));
		}
		http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
		http.logout(ServerHttpSecurity.LogoutSpec::disable);
		return http.build();
	}

	/**
	 * Whether the application has anything to authenticate a caller against.
	 */
	static class AnyAuthenticationAvailable extends AnyNestedCondition {

		AnyAuthenticationAvailable() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		/** A user directory. */
		@ConditionalOnBean(ReactiveUserDetailsService.class)
		static class OnUserDetailsService {

		}

		/** An authentication manager of its own. */
		@ConditionalOnBean(ReactiveAuthenticationManager.class)
		static class OnAuthenticationManager {

		}

		/** An issuer whose tokens it can validate. */
		@ConditionalOnBean(ReactiveJwtDecoder.class)
		static class OnJwtDecoder {

		}

	}

}
