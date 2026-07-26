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

import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * Auto-configuration tests for {@link HubApiSecurityAutoConfiguration}, checking that the
 * contributed chain matches the documentation endpoints and nothing else.
 */
class HubApiSecurityAutoConfigurationTests {

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HubApiSecurityAutoConfiguration.class))
		.withUserConfiguration(WebFluxSecurityConfiguration.class)
		.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true");

	@Test
	void chainIsAbsentWhenTheHubIsDisabled() {
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HubApiSecurityAutoConfiguration.class))
			.withUserConfiguration(WebFluxSecurityConfiguration.class)
			.run((context) -> assertThat(context).doesNotHaveBean("hubOpenapiSecurityWebFilterChain"));
	}

	@Test
	void chainMatchesTheDocumentationEndpoints() {
		this.runner.run((context) -> {
			assertThat(context).hasNotFailed();
			SecurityWebFilterChain chain = chain(context.getBean("hubOpenapiSecurityWebFilterChain"));
			assertThat(matches(chain, "/v3/api-docs")).isTrue();
			assertThat(matches(chain, "/v3/api-docs.yaml")).isTrue();
			assertThat(matches(chain, "/v3/api-docs/swagger-config")).isTrue();
			assertThat(matches(chain, "/v3/api-docs/service-a")).isTrue();
			assertThat(matches(chain, "/swagger-ui.html")).isTrue();
			assertThat(matches(chain, "/swagger-ui/index.html")).isTrue();
			assertThat(matches(chain, "/webjars/swagger-ui/swagger-ui.css")).isTrue();
		});
	}

	@Test
	void chainDoesNotMatchOtherGatewayRoutes() {
		this.runner.run((context) -> {
			SecurityWebFilterChain chain = chain(context.getBean("hubOpenapiSecurityWebFilterChain"));
			assertThat(matches(chain, "/api/orders")).isFalse();
			assertThat(matches(chain, "/webjars/other-lib/app.js")).isFalse();
			// The aggregated contracts are one segment deep, nothing more.
			assertThat(matches(chain, "/v3/api-docs/service-a/internal")).isFalse();
		});
	}

	@Test
	void configuredSpringDocPathsAreHonoured() {
		this.runner.withPropertyValues("springdoc.api-docs.path=/docs", "springdoc.swagger-ui.path=/docs-ui.html")
			.run((context) -> {
				SecurityWebFilterChain chain = chain(context.getBean("hubOpenapiSecurityWebFilterChain"));
				assertThat(matches(chain, "/docs")).isTrue();
				assertThat(matches(chain, "/docs-ui.html")).isTrue();
				// The aggregated contracts keep the path the hub publishes them under.
				assertThat(matches(chain, "/v3/api-docs/service-a")).isTrue();
			});
	}

	@Test
	void chainCanBeDisabled() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.security-chain-enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean("hubOpenapiSecurityWebFilterChain"));
	}

	@Test
	void applicationChainWithTheSameNameWins() {
		this.runner.withUserConfiguration(ApplicationChainConfiguration.class).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeansOfType(SecurityWebFilterChain.class)).hasSize(1);
		});
	}

	private static SecurityWebFilterChain chain(Object bean) {
		return (SecurityWebFilterChain) bean;
	}

	private static boolean matches(SecurityWebFilterChain chain, String path) {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
		return Boolean.TRUE.equals(chain.matches(exchange).defaultIfEmpty(false).block());
	}

	/**
	 * Provides the {@link ServerHttpSecurity} prototype the chain is built from, the
	 * default user the reactive security auto-configuration would generate, and the
	 * SpringDoc configuration.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableWebFluxSecurity
	@EnableConfigurationProperties
	static class WebFluxSecurityConfiguration {

		@Bean
		MapReactiveUserDetailsService userDetailsService() {
			return new MapReactiveUserDetailsService(
					User.withUsername("user").password("{noop}password").roles("USER").build());
		}

		@Bean
		@ConfigurationProperties("springdoc")
		SpringDocConfigProperties springDocConfigProperties() {
			return new SpringDocConfigProperties();
		}

		@Bean
		@ConfigurationProperties("springdoc.swagger-ui")
		SwaggerUiConfigProperties swaggerUiConfigProperties() {
			return new SwaggerUiConfigProperties();
		}

	}

	/**
	 * An application declaring the chain itself, under the name the plugin backs off
	 * from.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ApplicationChainConfiguration {

		@Bean
		SecurityWebFilterChain hubOpenapiSecurityWebFilterChain(ServerHttpSecurity http) {
			http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
			return http.build();
		}

	}

}
