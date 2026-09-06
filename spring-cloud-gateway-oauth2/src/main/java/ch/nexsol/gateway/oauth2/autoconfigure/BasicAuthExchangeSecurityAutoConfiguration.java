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

package ch.nexsol.gateway.oauth2.autoconfigure;

import ch.nexsol.gateway.oauth2.filter.webfilter.BasicAuthExchangeToAccessTokenGatewayWebFilter;
import ch.nexsol.gateway.oauth2.filter.webfilter.condition.BasicAuthExchangeConfiguredCondition;
import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties;
import ch.nexsol.gateway.oauth2.utils.SecurityUtils;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Auto-configuration contributing the security filter chain the Basic-auth to
 * access-token exchange needs: requests carrying the Basic credentials of a configured
 * client must reach the exchange filter instead of being challenged by the standard HTTP
 * Basic authentication of the application.
 * <p>
 * The chain declares no authorization rule, the exchange being what authorizes, so
 * everything its matcher accepts is served unchecked until the exchange filter has run.
 * Its matcher must therefore accept nothing the filter would skip &mdash; see
 * {@link ch.nexsol.gateway.oauth2.utils.SecurityUtils#isCandidateForExchange}.
 * <p>
 * The chain is only contributed when at least one client is configured, and it can be
 * turned off with
 * {@code spring.cloud.gateway.server.webflux.webfilter.basicauth-exchange-oauth2.security-chain-enabled=false}
 * or replaced by declaring a bean named {@code basicAuthExchangeSecurityWebFilterChain}.
 * <p>
 * Note that, as with any {@link SecurityWebFilterChain} bean, its mere presence makes
 * Spring Boot back off from its default "everything authenticated" chain: an application
 * relying on that default must declare its own chains.
 */
@AutoConfiguration(after = FiltersAutoConfiguration.class,
		afterName = "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration")
@ConditionalOnClass({ SecurityWebFilterChain.class, ServerHttpSecurity.class })
@Conditional(BasicAuthExchangeConfiguredCondition.class)
@ConditionalOnProperty(prefix = "spring.cloud.gateway.server.webflux.webfilter.basicauth-exchange-oauth2",
		name = "security-chain-enabled", matchIfMissing = true)
public class BasicAuthExchangeSecurityAutoConfiguration {

	/**
	 * Order of the contributed chain: ahead of the chains an application usually declares
	 * from {@code 1}, so the Basic credentials to exchange are let through first.
	 */
	public static final int BASIC_AUTH_EXCHANGE_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 200;

	/**
	 * Registers the chain matching the Basic-auth requests of the configured clients,
	 * with the exchange filter placed before authentication.
	 * @param http the reactive security builder
	 * @param properties the Basic-auth exchange configuration
	 * @param basicAuthExchangeFilter the filter performing the token exchange
	 * @return the Basic-auth exchange security filter chain
	 */
	@Bean
	@Order(BASIC_AUTH_EXCHANGE_CHAIN_ORDER)
	@ConditionalOnBean(ServerHttpSecurity.class)
	@ConditionalOnMissingBean(name = "basicAuthExchangeSecurityWebFilterChain")
	SecurityWebFilterChain basicAuthExchangeSecurityWebFilterChain(ServerHttpSecurity http,
			BasicAuthExchangeToAccessTokenProperties properties,
			BasicAuthExchangeToAccessTokenGatewayWebFilter basicAuthExchangeFilter) {
		http.cors(withDefaults());
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.securityMatcher(SecurityUtils.basicCredentialsMatcher(properties));
		http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
		http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
		http.logout(ServerHttpSecurity.LogoutSpec::disable);
		http.addFilterBefore(basicAuthExchangeFilter, SecurityWebFiltersOrder.AUTHENTICATION);
		return http.build();
	}

}
