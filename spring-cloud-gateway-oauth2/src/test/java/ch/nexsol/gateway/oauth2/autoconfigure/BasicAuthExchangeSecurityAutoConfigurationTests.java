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

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration tests for {@link BasicAuthExchangeSecurityAutoConfiguration},
 * checking that the security chain is only contributed when the exchange is configured,
 * and that the application keeps the last word on it.
 */
class BasicAuthExchangeSecurityAutoConfigurationTests {

	private static final String TOKEN_URIS = "spring.cloud.gateway.server.webflux.webfilter."
			+ "basicauth-exchange-oauth2.token-uris.alice=http://auth.example/token";

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(FiltersAutoConfiguration.class, BasicAuthExchangeSecurityAutoConfiguration.class))
		.withUserConfiguration(WebFluxSecurityConfiguration.class);

	@Test
	void chainIsAbsentWhenNoTokenUriIsConfigured() {
		this.runner.run((context) -> assertThat(context).doesNotHaveBean("basicAuthExchangeSecurityWebFilterChain"));
	}

	@Test
	void chainIsContributedWhenTheExchangeIsConfigured() {
		this.runner.withPropertyValues(TOKEN_URIS).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasBean("basicAuthExchangeSecurityWebFilterChain");
			assertThat(context).getBean("basicAuthExchangeSecurityWebFilterChain")
				.isInstanceOf(SecurityWebFilterChain.class);
		});
	}

	@Test
	void chainCanBeDisabled() {
		this.runner
			.withPropertyValues(TOKEN_URIS,
					"spring.cloud.gateway.server.webflux.webfilter."
							+ "basicauth-exchange-oauth2.security-chain-enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean("basicAuthExchangeSecurityWebFilterChain"));
	}

	@Test
	void applicationChainWithTheSameNameWins() {
		this.runner.withPropertyValues(TOKEN_URIS)
			.withUserConfiguration(ApplicationChainConfiguration.class)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeansOfType(SecurityWebFilterChain.class)).hasSize(1);
			});
	}

	/**
	 * Provides the {@link ServerHttpSecurity} prototype the chain is built from, and the
	 * default user the reactive security auto-configuration would generate.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableWebFluxSecurity
	static class WebFluxSecurityConfiguration {

		@Bean
		MapReactiveUserDetailsService userDetailsService() {
			return new MapReactiveUserDetailsService(
					User.withUsername("user").password("{noop}password").roles("USER").build());
		}

	}

	/**
	 * An application declaring the chain itself, under the name the plugin backs off
	 * from.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ApplicationChainConfiguration {

		@Bean
		SecurityWebFilterChain basicAuthExchangeSecurityWebFilterChain(ServerHttpSecurity http) {
			http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
			return http.build();
		}

	}

}
