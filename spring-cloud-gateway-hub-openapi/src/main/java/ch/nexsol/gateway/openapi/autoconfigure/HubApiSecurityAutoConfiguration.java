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

package ch.nexsol.gateway.openapi.autoconfigure;

import ch.nexsol.gateway.openapi.hub.HubOpenapiPaths;
import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Auto-configuration contributing the security filter chain of the OpenAPI hub when
 * Spring Security is active: without it the aggregated Swagger UI and the proxied
 * contracts are behind the authentication of the application.
 * <p>
 * The chain permits the SpringDoc endpoints as they are actually configured (a custom
 * {@code springdoc.api-docs.path} or {@code springdoc.swagger-ui.path} is honoured), the
 * Swagger UI assets, and the aggregated contracts the hub publishes as gateway routes
 * under {@link HubDiscoveryRouteLocator#API_DOCS_URL}. The latter carry a service name
 * that is only known at runtime, so they are matched one segment deep
 * ({@code /v3/api-docs/*}) rather than with a {@code /**} pattern.
 * <p>
 * The chain can be turned off with
 * {@code spring.cloud.gateway.server.webflux.hub-openapi.security-chain-enabled=false} or
 * replaced by declaring a bean named {@code hubOpenapiSecurityWebFilterChain}.
 * <p>
 * Note that, as with any {@link SecurityWebFilterChain} bean, its mere presence makes
 * Spring Boot back off from its default "everything authenticated" chain: an application
 * relying on that default must declare its own chains.
 */
@AutoConfiguration(after = HubApiAutoConfiguration.class,
		afterName = "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration")
@ConditionalOnClass({ SecurityWebFilterChain.class, ServerHttpSecurity.class })
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.hub-openapi.enabled", havingValue = "true",
		matchIfMissing = false)
public class HubApiSecurityAutoConfiguration {

	/**
	 * Order of the contributed chain: ahead of the chains an application usually declares
	 * from {@code 1}, so the documentation endpoints are served before any catch-all
	 * rule.
	 */
	public static final int HUB_OPENAPI_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 400;

	/**
	 * Registers the chain permitting the documentation endpoints of the hub.
	 * @param http the reactive security builder
	 * @param springDocProperties the SpringDoc configuration, when it is on
	 * @param swaggerUiProperties the Swagger UI configuration, when it is on
	 * @return the OpenAPI hub security filter chain
	 */
	@Bean
	@Order(HUB_OPENAPI_CHAIN_ORDER)
	@ConditionalOnBean(ServerHttpSecurity.class)
	@ConditionalOnMissingBean(name = "hubOpenapiSecurityWebFilterChain")
	@ConditionalOnProperty(prefix = "spring.cloud.gateway.server.webflux.hub-openapi", name = "security-chain-enabled",
			matchIfMissing = true)
	SecurityWebFilterChain hubOpenapiSecurityWebFilterChain(ServerHttpSecurity http,
			ObjectProvider<SpringDocConfigProperties> springDocProperties,
			ObjectProvider<SwaggerUiConfigProperties> swaggerUiProperties) {
		http.cors(withDefaults());
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(
				HubOpenapiPaths.documentationPaths(springDocProperties, swaggerUiProperties).toArray(String[]::new)));
		http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
		return http.build();
	}

}
