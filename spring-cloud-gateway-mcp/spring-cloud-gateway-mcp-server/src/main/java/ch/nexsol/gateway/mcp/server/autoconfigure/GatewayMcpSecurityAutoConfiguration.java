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

import ch.nexsol.gateway.commons.security.ConditionalOnPluginAuthentication;
import ch.nexsol.gateway.commons.security.IssuerJwtDecoders;
import ch.nexsol.gateway.commons.security.PluginSecurityChain;
import ch.nexsol.gateway.mcp.server.GatewayMcpProperties;
import ch.nexsol.gateway.mcp.server.oauth2.ProtectedResource;
import ch.nexsol.gateway.mcp.server.security.GatewayMcpSecurityCustomizer;

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
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * Auto-configuration securing the MCP endpoint on its own, so that the plugin needs no
 * console: a principal is required, presented as HTTP Basic &mdash; the local user of the
 * endpoint, else the user directory of the application &mdash; or as a Bearer token
 * validated against the issuer the plugin is given, else by the decoder the application
 * declared. The metadata of the protected resource, when published, stays open.
 * <p>
 * Ordered after the chain of the console, which governs the same paths when the console
 * is present and therefore answers first: with a console, this chain is never reached;
 * without one, it is the chain. An application replaces it by declaring a bean named
 * {@code gatewayMcpSecurityWebFilterChain}, or turns it off with
 * {@code spring.cloud.gateway.server.webflux.mcp.security-chain-enabled=false}.
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
	 * Order of the chain, after the console's and the OpenAPI hub's.
	 */
	public static final int GATEWAY_MCP_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 450;

	/**
	 * Builds the chain over the paths of the plugin: the endpoints require a principal,
	 * the metadata of the protected resource does not.
	 * @param http the reactive security builder
	 * @param properties the settings of the plugin
	 * @param streamable the endpoint of the stateless and streamable protocols
	 * @param sse the two endpoints of the SSE protocol
	 * @param resource the protected resource, when its metadata is published
	 * @param customizers the contributions, the Bearer challenge among them
	 * @param userDetailsService the user directory of the application, if it declared one
	 * @param authenticationManager the authentication manager of the application, if it
	 * declared one
	 * @param beanFactory the bean factory, where the decoder of the application is looked
	 * up when the JWT jars are on the classpath
	 * @return the chain
	 */
	@Bean
	@Order(GATEWAY_MCP_CHAIN_ORDER)
	@ConditionalOnBean(SecurityWebFilterChain.class)
	@ConditionalOnPluginAuthentication(prefix = "spring.cloud.gateway.server.webflux.mcp.security")
	@ConditionalOnMissingBean(name = "gatewayMcpSecurityWebFilterChain")
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.mcp.security-chain-enabled",
			havingValue = "true", matchIfMissing = true)
	SecurityWebFilterChain gatewayMcpSecurityWebFilterChain(ServerHttpSecurity http, GatewayMcpProperties properties,
			ObjectProvider<McpServerStreamableHttpProperties> streamable, ObjectProvider<McpServerSseProperties> sse,
			ObjectProvider<ProtectedResource> resource, ObjectProvider<GatewayMcpSecurityCustomizer> customizers,
			ObjectProvider<ReactiveUserDetailsService> userDetailsService,
			ObjectProvider<ReactiveAuthenticationManager> authenticationManager, BeanFactory beanFactory) {
		List<String> endpoints = GatewayMcpAutoConfiguration.endpoints(streamable, sse);
		List<String> open = new ArrayList<>();
		resource.ifAvailable((protectedResource) -> open.addAll(protectedResource.metadataPaths()));
		List<String> all = new ArrayList<>(open);
		all.addAll(endpoints);
		http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(all.toArray(String[]::new)));
		http.authorizeExchange((spec) -> {
			if (!open.isEmpty()) {
				spec.pathMatchers(open.toArray(String[]::new)).permitAll();
			}
			spec.anyExchange().authenticated();
		});
		PluginSecurityChain.basic(http, properties.getSecurity().getUser(), userDetailsService, authenticationManager);
		PluginSecurityChain.bearer(http, properties.getSecurity().getOauth2().getResourceserver().getJwt(),
				beanFactory);
		customizers.orderedStream().forEach((customizer) -> customizer.customize(http));
		return http.build();
	}

	/**
	 * The challenge of a {@code 401}, when the resource server classes are present and a
	 * Bearer token is accepted. Both Basic and Bearer pass; the challenge is the Bearer
	 * one, which is what an MCP client reads, and the discovery filter adds the metadata
	 * URL to it. Without a decoder the challenge stays the Basic one.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass({ ReactiveJwtDecoder.class, BearerTokenServerAuthenticationEntryPoint.class })
	static class BearerChallengeConfiguration {

		@Bean
		GatewayMcpSecurityCustomizer gatewayMcpBearerChallengeCustomizer(GatewayMcpProperties properties,
				ObjectProvider<ReactiveJwtDecoder> applicationDecoder) {
			boolean bearer = IssuerJwtDecoders
				.decoder(properties.getSecurity().getOauth2().getResourceserver().getJwt(),
						applicationDecoder.getIfAvailable())
				.isPresent();
			return (http) -> {
				if (bearer) {
					http.exceptionHandling((handling) -> handling
						.authenticationEntryPoint(new BearerTokenServerAuthenticationEntryPoint()));
				}
			};
		}

	}

}
