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
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Auto-configuration contributing the security filter chain the Basic-auth to
 * access-token exchange needs: a request whose Basic credentials were exchanged for an
 * access token is let through, rather than challenged by the authentication of the
 * application, which has no Basic credentials left to look at.
 * <p>
 * The chain permits what it matches, the exchange being what authorizes: the
 * authorization server refused the credentials or the filter answered {@code 401}, so a
 * request reaching this chain carries a token that server issued. It matches on the
 * attribute the filter sets, never on the {@code Authorization} header &mdash; see
 * {@link ch.nexsol.gateway.oauth2.utils.SecurityUtils#exchangedCredentialsMatcher()}.
 * <p>
 * A gateway that would rather validate the resulting token itself, or keep its own rules
 * over these requests, turns the chain off and lets them fall through to its chains.
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
	 * Registers the chain letting the requests the exchange authorized through.
	 * @param http the reactive security builder
	 * @return the Basic-auth exchange security filter chain
	 */
	@Bean
	@Order(BASIC_AUTH_EXCHANGE_CHAIN_ORDER)
	@ConditionalOnBean({ ServerHttpSecurity.class, BasicAuthExchangeToAccessTokenGatewayWebFilter.class })
	@ConditionalOnMissingBean(name = "basicAuthExchangeSecurityWebFilterChain")
	SecurityWebFilterChain basicAuthExchangeSecurityWebFilterChain(ServerHttpSecurity http) {
		http.cors(withDefaults());
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.securityMatcher(SecurityUtils.exchangedCredentialsMatcher());
		http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
		http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
		http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
		http.logout(ServerHttpSecurity.LogoutSpec::disable);
		return http.build();
	}

}
