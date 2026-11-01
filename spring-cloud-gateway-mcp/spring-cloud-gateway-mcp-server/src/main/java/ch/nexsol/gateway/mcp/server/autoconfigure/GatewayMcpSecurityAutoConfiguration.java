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
import java.util.Optional;

import ch.nexsol.gateway.commons.security.AudienceCheckingJwtDecoder;
import ch.nexsol.gateway.mcp.server.GatewayMcpProperties;
import ch.nexsol.gateway.mcp.server.oauth2.ProtectedResource;
import ch.nexsol.gateway.mcp.server.security.GatewayMcpSecurityCustomizer;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerSseProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.util.StringUtils;

import static org.springframework.security.config.Customizer.withDefaults;

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
 * The {@code ServerHttpSecurity} it builds from exists only once Boot's reactive security
 * auto-configuration ran, hence the ordering on it rather than on a bean it declares,
 * which is a prototype {@code @ConditionalOnBean} would not find.
 */
@AutoConfiguration(after = GatewayMcpAutoConfiguration.class,
		afterName = "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration")
@ConditionalOnClass({ SecurityWebFilterChain.class, ServerHttpSecurity.class })
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.mcp.enabled", havingValue = "true")
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
	 * @param customizers the contributions, the Bearer support among them
	 * @return the chain
	 */
	@Bean
	@Order(GATEWAY_MCP_CHAIN_ORDER)
	@ConditionalOnMissingBean(name = "gatewayMcpSecurityWebFilterChain")
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.mcp.security-chain-enabled",
			havingValue = "true", matchIfMissing = true)
	SecurityWebFilterChain gatewayMcpSecurityWebFilterChain(ServerHttpSecurity http, GatewayMcpProperties properties,
			ObjectProvider<McpServerStreamableHttpProperties> streamable, ObjectProvider<McpServerSseProperties> sse,
			ObjectProvider<ProtectedResource> resource, ObjectProvider<GatewayMcpSecurityCustomizer> customizers) {
		List<String> endpoints = GatewayMcpAutoConfiguration.endpoints(streamable, sse);
		List<String> open = new ArrayList<>();
		resource.ifAvailable((protectedResource) -> open.addAll(protectedResource.metadataPaths()));
		List<String> all = new ArrayList<>(open);
		all.addAll(endpoints);
		http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(all.toArray(String[]::new)));
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.authorizeExchange((spec) -> {
			if (!open.isEmpty()) {
				spec.pathMatchers(open.toArray(String[]::new)).permitAll();
			}
			spec.anyExchange().authenticated();
		});
		http.httpBasic(withDefaults());
		localUser(properties.getSecurity().getUser()).ifPresent(http::authenticationManager);
		http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
		http.logout(ServerHttpSecurity.LogoutSpec::disable);
		customizers.orderedStream().forEach((customizer) -> customizer.customize(http));
		return http.build();
	}

	/**
	 * The local user, held by this chain alone rather than published as a
	 * {@code ReactiveUserDetailsService}, so that it neither competes with nor replaces
	 * the authentication the rest of the application is built on. Without a password
	 * there is no local user.
	 */
	private static Optional<UserDetailsRepositoryReactiveAuthenticationManager> localUser(
			GatewayMcpProperties.User user) {
		if (!StringUtils.hasText(user.getPassword())) {
			return Optional.empty();
		}
		PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		// A password already carrying the id of the encoder that produced it is taken as
		// is, which is how a deployment keeps the clear text out of its configuration.
		String password = user.getPassword().startsWith("{") ? user.getPassword() : encoder.encode(user.getPassword());
		UserDetails details = User.withUsername(user.getName())
			.password(password)
			.roles(user.getRoles().toArray(String[]::new))
			.build();
		UserDetailsRepositoryReactiveAuthenticationManager manager = new UserDetailsRepositoryReactiveAuthenticationManager(
				new MapReactiveUserDetailsService(details));
		manager.setPasswordEncoder(encoder);
		return Optional.of(manager);
	}

	/**
	 * The Bearer support, when the resource server classes are present: the token is
	 * validated against the issuer the plugin is given, else by the decoder the
	 * application declared. With neither, no Bearer is accepted and the chain answers
	 * Basic alone.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass({ ReactiveJwtDecoder.class, BearerTokenServerAuthenticationEntryPoint.class })
	static class BearerConfiguration {

		@Bean
		GatewayMcpSecurityCustomizer gatewayMcpBearerCustomizer(GatewayMcpProperties properties,
				ObjectProvider<ReactiveJwtDecoder> applicationDecoder) {
			ReactiveJwtDecoder decoder = decoder(properties, applicationDecoder);
			return (http) -> {
				if (decoder == null) {
					return;
				}
				http.oauth2ResourceServer((oauth2) -> oauth2.jwt((jwt) -> jwt.jwtDecoder(decoder)));
				// Both Basic and Bearer are accepted; the challenge of a 401 is the
				// Bearer
				// one, which is what an MCP client reads, and the discovery filter adds
				// the metadata URL to it.
				http.exceptionHandling((handling) -> handling
					.authenticationEntryPoint(new BearerTokenServerAuthenticationEntryPoint()));
			};
		}

		/**
		 * The issuer is asked for its keys on the first token that arrives rather than at
		 * start-up, so a gateway comes up whether or not the provider is answering.
		 */
		private static ReactiveJwtDecoder decoder(GatewayMcpProperties properties,
				ObjectProvider<ReactiveJwtDecoder> applicationDecoder) {
			GatewayMcpProperties.OAuth2.ResourceServer.Jwt jwt = properties.getSecurity()
				.getOauth2()
				.getResourceserver()
				.getJwt();
			String issuer = jwt.getIssuerUri();
			if (!StringUtils.hasText(issuer)) {
				return applicationDecoder.getIfAvailable();
			}
			ReactiveJwtDecoder decoder = new SupplierReactiveJwtDecoder(
					() -> ReactiveJwtDecoders.fromIssuerLocation(issuer));
			if (jwt.getAudiences().isEmpty()) {
				return decoder;
			}
			return new AudienceCheckingJwtDecoder(decoder, jwt.getAudiences());
		}

	}

}
