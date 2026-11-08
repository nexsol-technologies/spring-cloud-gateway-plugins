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

import ch.nexsol.gateway.commons.security.ConditionalOnPluginAuthentication;
import ch.nexsol.gateway.commons.security.PluginSecurityChain;
import ch.nexsol.gateway.database.RouteManagementPaths;
import ch.nexsol.gateway.database.RoutesDatabaseProperties;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
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
 * <li>It is only contributed when there is something to authenticate against &mdash; the
 * local user or the issuer the plugin is given under
 * {@code spring.cloud.gateway.server.webflux.routes-database.security}, else the user
 * directory, the authentication manager or the issuer of the application. Closing a door
 * with no key behind it would leave no way through it at all, and turn adding this plugin
 * into an outage. A gateway with no authentication at all closes these endpoints with
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
				"ch.nexsol.gateway.oauth2.autoconfigure.BasicAuthExchangeSecurityAutoConfiguration",
				"ch.nexsol.gateway.mcp.server.autoconfigure.GatewayMcpSecurityAutoConfiguration" })
@ConditionalOnClass({ SecurityWebFilterChain.class, ServerHttpSecurity.class })
@EnableConfigurationProperties(RoutesDatabaseProperties.class)
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
	 * @param properties the settings of the plugin, its own credentials among them
	 * @param userDetailsService the user directory of the application, if it declared one
	 * @param authenticationManager the authentication manager of the application, if it
	 * declared one
	 * @param beanFactory the bean factory, where the decoder of the application is looked
	 * up when the JWT jars are on the classpath
	 * @return the route management security filter chain
	 */
	@Bean
	@Order(ROUTE_MANAGEMENT_CHAIN_ORDER)
	// The declaration of the API rather than the plugin itself: an application that
	// switched the whole auto-configuration off serves none of these paths, and closing
	// what nobody answers would turn a 404 into a 401.
	@ConditionalOnBean(value = SecurityWebFilterChain.class, name = "routeApiSecuredPaths")
	@ConditionalOnPluginAuthentication(prefix = "spring.cloud.gateway.server.webflux.routes-database.security")
	@ConditionalOnMissingBean(name = "routeManagementSecurityWebFilterChain")
	SecurityWebFilterChain routeManagementSecurityWebFilterChain(ServerHttpSecurity http,
			RoutesDatabaseProperties properties, ObjectProvider<ReactiveUserDetailsService> userDetailsService,
			ObjectProvider<ReactiveAuthenticationManager> authenticationManager, BeanFactory beanFactory) {
		http.cors(withDefaults());
		http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(RouteManagementPaths.API.toArray(String[]::new)));
		http.authorizeExchange((spec) -> spec.anyExchange().authenticated());
		PluginSecurityChain.basic(http, properties.getSecurity().getUser(), userDetailsService, authenticationManager);
		PluginSecurityChain.bearer(http, properties.getSecurity().getOauth2().getResourceserver().getJwt(),
				beanFactory);
		return http.build();
	}

}
