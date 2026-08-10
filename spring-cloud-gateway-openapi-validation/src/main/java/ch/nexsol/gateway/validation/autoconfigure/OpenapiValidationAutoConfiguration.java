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
import ch.nexsol.gateway.validation.factory.OpenapiValidationGatewayFilterFactory;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

/**
 * Auto-configuration registering the OpenAPI validation filter factory. The filter is
 * inert until a route references it, so it is registered unconditionally; a route that
 * already uses it is turned off by setting both directions to {@code OFF}.
 */
@AutoConfiguration
@EnableConfigurationProperties(OpenapiValidationProperties.class)
public class OpenapiValidationAutoConfiguration {

	/**
	 * Registers the registry the contracts are read and kept in.
	 * @param resourceLoader the loader used to reach a contract that is not addressed by
	 * an {@code http(s)} URL
	 * @return the contract registry bean
	 */
	@Bean
	@ConditionalOnMissingBean
	OpenapiContractRegistry openapiContractRegistry(ResourceLoader resourceLoader) {
		return new OpenapiContractRegistry(resourceLoader);
	}

	/**
	 * Registers the counters the validation outcomes are published to. The meter registry
	 * is optional, so the filter behaves the same on a gateway that exposes no metrics at
	 * all.
	 * @param meterRegistry the registry the meters are published to, when there is one
	 * @return the validation metrics bean
	 */
	@Bean
	@ConditionalOnMissingBean
	ValidationMetrics validationMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
		return new ValidationMetrics(meterRegistry.getIfAvailable());
	}

	/**
	 * Registers the validation filter factory.
	 * @param registry the registry the contracts are read through
	 * @param properties the per direction validation settings
	 * @param metrics the counters the outcomes are published to
	 * @return the filter factory bean
	 */
	@Bean
	OpenapiValidationGatewayFilterFactory openapiValidationGatewayFilterFactory(OpenapiContractRegistry registry,
			OpenapiValidationProperties properties, ValidationMetrics metrics) {
		return new OpenapiValidationGatewayFilterFactory(registry, properties, metrics);
	}

}
