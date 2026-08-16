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

import ch.nexsol.gateway.commons.security.SecuredPaths;
import ch.nexsol.gateway.database.RouteManagementPaths;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration tests for {@link GatewayDatabaseSecurityAutoConfiguration}: what the
 * chain matches, and the two cases where contributing it would make a gateway less safe
 * rather than more.
 */
class GatewayDatabaseSecurityAutoConfigurationTests {

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(GatewayDatabaseSecurityAutoConfiguration.class))
		.withUserConfiguration(RouteApiDeclarationConfiguration.class, WebFluxSecurityConfiguration.class,
				UserDirectoryConfiguration.class, ApplicationChainConfiguration.class);

	@Test
	void chainMatchesWhatChangesTheRoutingTable() {
		this.runner.run((context) -> {
			assertThat(context).hasNotFailed();
			SecurityWebFilterChain chain = (SecurityWebFilterChain) context
				.getBean("routeManagementSecurityWebFilterChain");
			assertThat(matches(chain, "/api/gateway/routes")).isTrue();
			assertThat(matches(chain, "/api/gateway/routes/12")).isTrue();
			assertThat(matches(chain, "/api/gateway/routes/available-predicates")).isTrue();
		});
	}

	@Test
	void chainDoesNotMatchTheRestOfTheGateway() {
		this.runner.run((context) -> {
			SecurityWebFilterChain chain = (SecurityWebFilterChain) context
				.getBean("routeManagementSecurityWebFilterChain");
			assertThat(matches(chain, "/api/orders")).isFalse();
			// A gateway route published under the same prefix is not this plugin.
			assertThat(matches(chain, "/api/gateway/routes/12/history")).isFalse();
			// The page over the same routes is a view of the console, governed by the
			// chain the console contributes rather than by this one.
			assertThat(matches(chain, "/ui/routes/db")).isFalse();
			assertThat(matches(chain, "/ui/routes")).isFalse();
		});
	}

	@Test
	void backsOffWhenItWouldDisplaceTheDefaultChainOfSpringSecurity() {
		// With no other chain, Spring Security serves its own "everything authenticated"
		// chain, which already covers these paths. Replacing it with one matching the
		// route management alone would leave every other path of the application open.
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GatewayDatabaseSecurityAutoConfiguration.class))
			.withUserConfiguration(RouteApiDeclarationConfiguration.class, WebFluxSecurityConfiguration.class,
					UserDirectoryConfiguration.class)
			.run((context) -> assertThat(context).doesNotHaveBean("routeManagementSecurityWebFilterChain"));
	}

	@Test
	void backsOffWhenThereIsNothingToAuthenticateAgainst() {
		// Closing a door with no key behind it would leave no way through it at all.
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GatewayDatabaseSecurityAutoConfiguration.class))
			.withUserConfiguration(RouteApiDeclarationConfiguration.class, WebFluxSecurityConfiguration.class,
					ApplicationChainConfiguration.class)
			.run((context) -> assertThat(context).doesNotHaveBean("routeManagementSecurityWebFilterChain"));
	}

	@Test
	void backsOffWhenThePluginPublishesNothing() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.routes-database.access=none")
			.run((context) -> assertThat(context).doesNotHaveBean("routeManagementSecurityWebFilterChain"));
	}

	@Test
	void chainCanBeDisabled() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.routes-database.security-chain-enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean("routeManagementSecurityWebFilterChain"));
	}

	@Test
	void applicationChainWithTheSameNameWins() {
		this.runner.withUserConfiguration(NamedApplicationChainConfiguration.class)
			.run((context) -> assertThat(context.getBeansOfType(SecurityWebFilterChain.class)).hasSize(2));
	}

	private static boolean matches(SecurityWebFilterChain chain, String path) {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
		return Boolean.TRUE.equals(chain.matches(exchange).defaultIfEmpty(false).block());
	}

	/**
	 * Stands in for the declaration the plugin makes when it publishes its API, which is
	 * what the chain keys on: an application that switched the whole auto-configuration
	 * off serves none of these paths, and closing what nobody answers would turn a
	 * {@code 404} into a {@code 401}.
	 */
	@Configuration(proxyBeanMethods = false)
	static class RouteApiDeclarationConfiguration {

		@Bean
		SecuredPaths routeApiSecuredPaths() {
			return SecuredPaths.api(RouteManagementPaths.API.toArray(String[]::new));
		}

	}

	/**
	 * Provides the {@link ServerHttpSecurity} prototype the chain is built from.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableWebFluxSecurity
	static class WebFluxSecurityConfiguration {

	}

	/**
	 * The user directory the chain would authenticate against, kept apart so a context
	 * can be assembled without one.
	 */
	@Configuration(proxyBeanMethods = false)
	static class UserDirectoryConfiguration {

		@Bean
		MapReactiveUserDetailsService userDetailsService() {
			return new MapReactiveUserDetailsService(
					User.withUsername("user").password("{noop}password").roles("USER").build());
		}

	}

	/**
	 * An application, or another plugin, that has already taken over from the default
	 * chain of Spring Security.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ApplicationChainConfiguration {

		@Bean
		SecurityWebFilterChain applicationWebFilterChain(ServerHttpSecurity http) {
			http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
			return http.build();
		}

	}

	/**
	 * An application declaring the chain itself, under the name the plugin backs off
	 * from.
	 */
	@Configuration(proxyBeanMethods = false)
	static class NamedApplicationChainConfiguration {

		@Bean
		SecurityWebFilterChain routeManagementSecurityWebFilterChain(ServerHttpSecurity http) {
			http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
			return http.build();
		}

	}

}
