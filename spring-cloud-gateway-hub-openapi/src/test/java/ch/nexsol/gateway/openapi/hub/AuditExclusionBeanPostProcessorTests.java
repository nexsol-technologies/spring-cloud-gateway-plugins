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

package ch.nexsol.gateway.openapi.hub;

import java.util.List;

import ch.nexsol.gateway.audit.AuditProperties;
import ch.nexsol.gateway.audit.autoconfigure.AuditAutoConfiguration;
import ch.nexsol.gateway.openapi.autoconfigure.HubApiAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import reactor.core.publisher.Flux;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.filter.factory.rewrite.GzipMessageBodyResolver;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the documentation endpoints of the hub are kept out of the global auditing
 * web filter.
 */
class AuditExclusionBeanPostProcessorTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
				HubApiAutoConfiguration.class, AuditAutoConfiguration.class))
		.withUserConfiguration(GatewayScaffoldingConfiguration.class)
		.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.gateway-uri=http://gateway.example",
				"spring.cloud.gateway.server.webflux.hub-openapi.enabled=true");

	@Test
	void excludesTheDocumentationEndpointsTheHubServes() {
		this.runner.run((context) -> assertThat(excludedPaths(context)).contains("/v3/api-docs", "/v3/api-docs.yaml",
				"/v3/api-docs/swagger-config", "/v3/api-docs/*", "/swagger-ui.html", "/swagger-ui/**",
				"/webjars/swagger-ui/**"));
	}

	@Test
	void honoursACustomApiDocsPath() {
		this.runner.withPropertyValues("springdoc.api-docs.path=/contracts").run((context) -> {
			assertThat(excludedPaths(context)).contains("/contracts", "/contracts.yaml", "/contracts/swagger-config");
			assertThat(excludedPaths(context)).doesNotContain("/v3/api-docs.yaml");
		});
	}

	@Test
	void keepsWhatTheApplicationExcludedAndAddsEachPathOnce() {
		this.runner
			.withPropertyValues(
					"spring.cloud.gateway.server.webflux.audit.web-filter.exclude-paths=/v3/api-docs,/actuator/**")
			.run((context) -> assertThat(excludedPaths(context)).containsOnlyOnce("/v3/api-docs")
				.contains("/actuator/**", "/v3/api-docs.yaml"));
	}

	@Test
	void excludesNothingWhenTheHubIsDisabled() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=false")
			.run((context) -> assertThat(excludedPaths(context)).isEmpty());
	}

	private static List<String> excludedPaths(AssertableApplicationContext context) {
		return context.getBean(AuditProperties.class).getWebFilter().getExcludePaths();
	}

	/**
	 * The gateway and SpringDoc beans the hub is built on, which the runner does not
	 * auto-configure.
	 */
	@Configuration(proxyBeanMethods = false)
	static class GatewayScaffoldingConfiguration {

		@Bean
		RouteLocator routeLocator() {
			return Flux::empty;
		}

		@Bean
		SwaggerUiConfigProperties swaggerUiConfigProperties() {
			return new SwaggerUiConfigProperties();
		}

		@Bean
		SpringDocConfigProperties springDocConfigProperties() {
			return new SpringDocConfigProperties();
		}

		@Bean
		ServerCodecConfigurer serverCodecConfigurer() {
			return ServerCodecConfigurer.create();
		}

		@Bean
		GzipMessageBodyResolver gzipMessageBodyResolver() {
			return new GzipMessageBodyResolver();
		}

	}

}
