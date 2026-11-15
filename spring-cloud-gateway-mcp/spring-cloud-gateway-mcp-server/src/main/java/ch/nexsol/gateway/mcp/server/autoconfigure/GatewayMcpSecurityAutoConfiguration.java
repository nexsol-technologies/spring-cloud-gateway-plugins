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

package ch.nexsol.gateway.mcp.server.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import ch.nexsol.gateway.commons.security.ConditionalOnPluginAuthentication;
import ch.nexsol.gateway.commons.security.PluginSecurityChain;
import ch.nexsol.gateway.mcp.server.GatewayMcpProperties;
import ch.nexsol.gateway.mcp.server.oauth2.ProtectedResource;
import ch.nexsol.gateway.mcp.server.security.GatewayMcpSecurityCustomizer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStdioDisabledCondition;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerSseProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;

/**
 * Auto-configuration securing the MCP endpoint on its own, so that the plugin needs no
 * console: a principal is required, presented as HTTP Basic &mdash; the local user of the
 * endpoint, else the user directory of the application &mdash; or as a Bearer token
 * validated against the issuer the plugin is given, else by the decoder the application
 * declared. The metadata of the protected resource, when published, stays open.
 * <p>
 * An MCP client presents credentials of its own, a token it obtained or a user it was
 * configured with, and never goes through the login of the console. Given credentials of
 * its own &mdash; a local user or an issuer under {@code ...mcp.security} &mdash; the
 * chain therefore answers ahead of the chain of the console, which declares the same
 * paths. Given none, it stays behind it: with a console, the console governs the
 * endpoint; without one, this is the chain. An application replaces it by declaring a
 * bean named {@code gatewayMcpSecurityWebFilterChain}, or turns it off with
 * {@code spring.cloud.gateway.server.webflux.mcp.security-chain-enabled=false}.
 * <p>
 * The chain answers ahead of the chains of the application, so a rule the application
 * wrote for the endpoint in its own chain is no longer reached: what it asked of a caller
 * there is said here, as {@code security.required-roles}, or the chain is turned off.
 * <p>
 * Two conditions keep the plugin from making a gateway <em>less</em> safe by being added
 * to it:
 * <ul>
 * <li>The chain is only contributed when another {@link SecurityWebFilterChain} already
 * exists. Spring Security serves its own "everything authenticated" chain as long as
 * there is none, and that chain already covers the endpoint; replacing it with one that
 * matches the endpoint alone would leave every other path of the application
 * unguarded.</li>
 * <li>It is only contributed when there is something to authenticate against &mdash; the
 * local user or the issuer the plugin is given, else the user directory, the
 * authentication manager or the issuer of the application. Closing a door with no key
 * behind it would leave no way through it at all.</li>
 * </ul>
 * <p>
 * The {@code ServerHttpSecurity} it builds from exists only once Boot's reactive security
 * auto-configuration ran, hence the ordering on it rather than on a bean it declares,
 * which is a prototype {@code @ConditionalOnBean} would not find.
 */
@AutoConfiguration(after = GatewayMcpAutoConfiguration.class,
		afterName = {
				"org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration",
				"ch.nexsol.gateway.ui.autoconfigure.GatewayUiSecurityAutoConfiguration",
				"ch.nexsol.gateway.openapi.autoconfigure.HubApiSecurityAutoConfiguration",
				"ch.nexsol.gateway.routes.security.autoconfigure.RoutesSecurityAutoConfiguration",
				"ch.nexsol.gateway.oauth2.autoconfigure.BasicAuthExchangeSecurityAutoConfiguration" })
@ConditionalOnClass({ SecurityWebFilterChain.class, ServerHttpSecurity.class })
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.mcp.enabled", havingValue = "true")
// Spring AI's own test for an HTTP transport: over stdio there is no endpoint to secure.
@Conditional(McpServerStdioDisabledCondition.class)
public class GatewayMcpSecurityAutoConfiguration {

	/**
	 * Order of the chain given no credentials of its own, after the console's and the
	 * OpenAPI hub's.
	 */
	public static final int GATEWAY_MCP_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 450;

	/**
	 * Order of the chain given credentials of its own, ahead of the console's.
	 */
	public static final int GATEWAY_MCP_OWN_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 250;

	private static final String SECURITY_PREFIX = "spring.cloud.gateway.server.webflux.mcp.security";

	private static final String ROLE_PREFIX = "ROLE_";

	/**
	 * Builds the chain over the paths of the plugin: the endpoints require a principal,
	 * holding one of the required roles when some are named, the metadata of the
	 * protected resource does not.
	 * @param http the reactive security builder
	 * @param properties the settings of the plugin
	 * @param streamable the endpoint of the stateless and streamable protocols
	 * @param sse the two endpoints of the SSE protocol
	 * @param resource the protected resource, when its metadata is published
	 * @param customizers the contributions of the application
	 * @param userDetailsService the user directory of the application, if it declared one
	 * @param authenticationManager the authentication manager of the application, if it
	 * declared one
	 * @param beanFactory the bean factory, where the decoder of the application is looked
	 * up when the JWT jars are on the classpath
	 * @return the chain
	 */
	// No @Order here: Spring reads the one of the method before asking the bean, and the
	// order depends on the configuration.
	@Bean
	@ConditionalOnBean(SecurityWebFilterChain.class)
	@ConditionalOnPluginAuthentication(prefix = SECURITY_PREFIX)
	@ConditionalOnMissingBean(name = "gatewayMcpSecurityWebFilterChain")
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.mcp.security-chain-enabled",
			havingValue = "true", matchIfMissing = true)
	SecurityWebFilterChain gatewayMcpSecurityWebFilterChain(ServerHttpSecurity http, GatewayMcpProperties properties,
			ObjectProvider<McpServerStreamableHttpProperties> streamable, ObjectProvider<McpServerSseProperties> sse,
			ObjectProvider<ProtectedResource> resource, ObjectProvider<GatewayMcpSecurityCustomizer> customizers,
			ObjectProvider<ReactiveUserDetailsService> userDetailsService,
			ObjectProvider<ReactiveAuthenticationManager> authenticationManager, BeanFactory beanFactory) {
		List<String> all = new ArrayList<>(GatewayMcpAutoConfiguration.endpoints(streamable, sse));
		List<String> open = new ArrayList<>();
		resource.ifAvailable((protectedResource) -> open.addAll(protectedResource.metadataPaths()));
		all.addAll(open);
		// A path matcher over no path does not build, and the server serves none when the
		// application kept its HTTP transport out: the chain then governs nothing.
		http.securityMatcher(all.isEmpty() ? (exchange) -> ServerWebExchangeMatcher.MatchResult.notMatch()
				: ServerWebExchangeMatchers.pathMatchers(all.toArray(String[]::new)));
		GatewayMcpProperties.Security security = properties.getSecurity();
		http.authorizeExchange((spec) -> {
			if (!open.isEmpty()) {
				spec.pathMatchers(open.toArray(String[]::new)).permitAll();
			}
			List<String> roles = security.getRequiredRoles();
			if (roles.isEmpty()) {
				spec.anyExchange().authenticated();
			}
			else {
				spec.anyExchange().access(anyRole(roles));
			}
		});
		boolean basic = PluginSecurityChain.basic(http, security.getUser(), userDetailsService, authenticationManager);
		boolean bearer = PluginSecurityChain.bearer(http, security.getOauth2().getResourceserver().getJwt(),
				beanFactory);
		PluginSecurityChain.requireCredentials(basic, bearer, SECURITY_PREFIX);
		if (bearer) {
			// Resolved here and nowhere above, so that this class links without the
			// resource server jar.
			McpBearerSupport.configure(http, security.getRolesClaim());
		}
		customizers.orderedStream().forEach((customizer) -> customizer.customize(http));
		return new OrderedChain(http.build(),
				ownCredentials(security) ? GATEWAY_MCP_OWN_CHAIN_ORDER : GATEWAY_MCP_CHAIN_ORDER);
	}

	private static boolean ownCredentials(GatewayMcpProperties.Security security) {
		return StringUtils.hasText(security.getUser().getPassword())
				|| StringUtils.hasText(security.getOauth2().getResourceserver().getJwt().getIssuerUri());
	}

	/**
	 * Grants a principal holding one of the roles, whatever their case: the roles of a
	 * token are read upper-cased while those of a user are kept as written, so the same
	 * role compared as is would pass from one credential and not from the other. The
	 * {@code ROLE_} prefix is added to what is required and never to what is held: an
	 * authority without it is not a role.
	 */
	private static ReactiveAuthorizationManager<AuthorizationContext> anyRole(List<String> roles) {
		Set<String> wanted = roles.stream()
			.map((role) -> role.toUpperCase(Locale.ROOT))
			.map((role) -> role.startsWith(ROLE_PREFIX) ? role : ROLE_PREFIX + role)
			.collect(Collectors.toSet());
		return (authentication, context) -> authentication.filter(Authentication::isAuthenticated)
			.map((principal) -> principal.getAuthorities()
				.stream()
				.map(GrantedAuthority::getAuthority)
				.filter(Objects::nonNull)
				.anyMatch((held) -> wanted.contains(held.toUpperCase(Locale.ROOT))))
			.defaultIfEmpty(false)
			.map(AuthorizationDecision::new);
	}

	/**
	 * The chain, carrying its order.
	 *
	 * @param chain the chain
	 * @param order its order among the chains of the application
	 */
	private record OrderedChain(SecurityWebFilterChain chain, int order) implements SecurityWebFilterChain, Ordered {

		@Override
		public Mono<Boolean> matches(ServerWebExchange exchange) {
			return this.chain.matches(exchange);
		}

		@Override
		public Flux<WebFilter> getWebFilters() {
			return this.chain.getWebFilters();
		}

		@Override
		public int getOrder() {
			return this.order;
		}

	}

}
