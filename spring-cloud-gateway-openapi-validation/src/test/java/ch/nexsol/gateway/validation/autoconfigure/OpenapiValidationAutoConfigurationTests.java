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

package ch.nexsol.gateway.validation.autoconfigure;

import ch.nexsol.gateway.validation.OpenapiContractRegistry;
import ch.nexsol.gateway.validation.OpenapiValidationProperties;
import ch.nexsol.gateway.validation.ValidationMetrics;
import ch.nexsol.gateway.validation.ValidationMode;
import ch.nexsol.gateway.validation.factory.OpenapiValidationGatewayFilterFactory;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the plugin registers its filter factory, and that the defaults are the
 * safe ones: requests enforced, responses only reported on.
 */
class OpenapiValidationAutoConfigurationTests {

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OpenapiValidationAutoConfiguration.class));

	@Test
	void registersTheFilterFactory() {
		this.runner.run((context) -> assertThat(context).hasSingleBean(OpenapiValidationGatewayFilterFactory.class)
			.hasSingleBean(OpenapiContractRegistry.class)
			.hasSingleBean(ValidationMetrics.class)
			.hasSingleBean(OpenapiValidationProperties.class));
	}

	@Test
	void enforcesTheRequestAndOnlyReportsOnTheResponseByDefault() {
		this.runner.run((context) -> {
			OpenapiValidationProperties properties = context.getBean(OpenapiValidationProperties.class);
			assertThat(properties.getRequest().getMode()).isEqualTo(ValidationMode.ENFORCE);
			assertThat(properties.getResponse().getMode()).isEqualTo(ValidationMode.REPORT);
			assertThat(properties.getRequest().isValidateBody()).isTrue();
			assertThat(properties.getResponse().isValidateBody()).isTrue();
		});
	}

	/**
	 * Binds every setting of both directions, not just the mode. A direction is a plain
	 * java bean on purpose: giving it a single parameterised constructor would turn it
	 * into a constructor-bound value object, and everything but that constructor argument
	 * would silently stop being bindable.
	 */
	@Test
	void bindsEverySettingOfBothDirections() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.openapi-validation.request.mode=REPORT",
					"spring.cloud.gateway.server.webflux.openapi-validation.request.validate-body=false",
					"spring.cloud.gateway.server.webflux.openapi-validation.request.max-body-size=64KB",
					"spring.cloud.gateway.server.webflux.openapi-validation.response.mode=OFF",
					"spring.cloud.gateway.server.webflux.openapi-validation.response.max-body-size=256KB")
			.run((context) -> {
				OpenapiValidationProperties properties = context.getBean(OpenapiValidationProperties.class);
				assertThat(properties.getRequest().getMode()).isEqualTo(ValidationMode.REPORT);
				assertThat(properties.getRequest().isValidateBody()).isFalse();
				assertThat(properties.getRequest().getMaxBodySize().toKilobytes()).isEqualTo(64);
				assertThat(properties.getResponse().getMode()).isEqualTo(ValidationMode.OFF);
				assertThat(properties.getResponse().isActive()).isFalse();
				assertThat(properties.getResponse().getMaxBodySize().toKilobytes()).isEqualTo(256);
				// Untouched by the binding above, so still the default.
				assertThat(properties.getResponse().isValidateBody()).isTrue();
			});
	}

	@Test
	void worksWithoutAMeterRegistry() {
		this.runner.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean("meterRegistry");
			// The no-op instance records nothing rather than failing.
			context.getBean(ValidationMetrics.class).contractUnavailable("classpath:none.yaml");
		});
	}

}
