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

package ch.nexsol.gateway.ui.autoconfigure;

import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.ui.security.LoginController;
import ch.nexsol.gateway.ui.security.UiSecurityModelAttributes;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
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
 * Auto-configuration tests for {@link GatewayUiSecurityAutoConfiguration}, checking that
 * the contributed chain matches the paths the shell serves and nothing else.
 */
class GatewayUiSecurityAutoConfigurationTests {

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(GatewayUiAutoConfiguration.class, GatewayUiSecurityAutoConfiguration.class))
		.withUserConfiguration(WebFluxSecurityConfiguration.class);

	@Test
	void chainMatchesTheShellAndItsAssets() {
		this.runner.run((context) -> {
			assertThat(context).hasNotFailed();
			SecurityWebFilterChain chain = (SecurityWebFilterChain) context.getBean("gatewayUiSecurityWebFilterChain");
			assertThat(matches(chain, "/ui")).isTrue();
			assertThat(matches(chain, "/css/gateway-ui.css")).isTrue();
			assertThat(matches(chain, "/js/htmx.min.js")).isTrue();
			// The brand images: the favicon and sidebar icon every page loads, and the
			// lockup of the home page.
			assertThat(matches(chain, "/img/icon.png")).isTrue();
			assertThat(matches(chain, "/img/logo.png")).isTrue();
			assertThat(matches(chain, "/img/logo-dark.png")).isTrue();
		});
	}

	@Test
	void chainMatchesTheSourceMapsTheShippedAssetsPointAt() {
		// The minified Bootstrap files carry a sourceMappingURL, so a browser with its
		// developer tools open asks for maps this module does not ship. Those requests
		// must read as the 404 they are, not as a 401.
		this.runner.run((context) -> {
			SecurityWebFilterChain chain = (SecurityWebFilterChain) context.getBean("gatewayUiSecurityWebFilterChain");
			assertThat(matches(chain, "/js/bootstrap.bundle.min.js.map")).isTrue();
			assertThat(matches(chain, "/css/bootstrap.min.css.map")).isTrue();
		});
	}

	@Test
	void chainDoesNotMatchGatewayRoutesUnderTheUiPrefix() {
		this.runner.run((context) -> {
			SecurityWebFilterChain chain = (SecurityWebFilterChain) context.getBean("gatewayUiSecurityWebFilterChain");
			assertThat(matches(chain, "/ui/find_pwd")).isFalse();
			// The database routes view declares its own paths, and only when the plugin
			// serving it is on the classpath, which it is not here.
			assertThat(matches(chain, "/ui/routes/db")).isFalse();
		});
	}

	@Test
	void viewsThatAreNotActiveContributeNoPath() {
		this.runner.run((context) -> {
			SecurityWebFilterChain chain = (SecurityWebFilterChain) context.getBean("gatewayUiSecurityWebFilterChain");
			assertThat(matches(chain, "/ui/audit")).isTrue();
		});
		this.runner.withClassLoader(new FilteredClassLoader(AuditEventPublisher.class)).run((context) -> {
			SecurityWebFilterChain chain = (SecurityWebFilterChain) context.getBean("gatewayUiSecurityWebFilterChain");
			assertThat(matches(chain, "/ui/audit")).isFalse();
			assertThat(matches(chain, "/ui")).isTrue();
		});
	}

	@Test
	void openConsoleServesNoLoginExchange() {
		this.runner.run((context) -> {
			assertThat(context).doesNotHaveBean(LoginController.class);
			assertThat(context).doesNotHaveBean(UiSecurityModelAttributes.class);
			SecurityWebFilterChain chain = (SecurityWebFilterChain) context.getBean("gatewayUiSecurityWebFilterChain");
			assertThat(matches(chain, "/ui/login")).isFalse();
		});
	}

	@Test
	void authenticatedConsoleServesItsLoginExchange() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.ui.security.mode=authenticated")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(LoginController.class);
				assertThat(context).hasSingleBean(UiSecurityModelAttributes.class);
				SecurityWebFilterChain chain = (SecurityWebFilterChain) context
					.getBean("gatewayUiSecurityWebFilterChain");
				assertThat(matches(chain, "/ui/login")).isTrue();
				assertThat(matches(chain, "/ui/logout")).isTrue();
				// The paths of the views are still matched exactly, and a gateway route
				// under /ui still is not.
				assertThat(matches(chain, "/ui")).isTrue();
				assertThat(matches(chain, "/ui/find_pwd")).isFalse();
			});
	}

	@Test
	void chainCanBeDisabled() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.ui.security-chain-enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean("gatewayUiSecurityWebFilterChain"));
	}

	@Test
	void applicationChainWithTheSameNameWins() {
		this.runner.withUserConfiguration(ApplicationChainConfiguration.class).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeansOfType(SecurityWebFilterChain.class)).hasSize(1);
		});
	}

	private static boolean matches(SecurityWebFilterChain chain, String path) {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
		return Boolean.TRUE.equals(chain.matches(exchange).defaultIfEmpty(false).block());
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
		SecurityWebFilterChain gatewayUiSecurityWebFilterChain(ServerHttpSecurity http) {
			http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
			return http.build();
		}

	}

}
