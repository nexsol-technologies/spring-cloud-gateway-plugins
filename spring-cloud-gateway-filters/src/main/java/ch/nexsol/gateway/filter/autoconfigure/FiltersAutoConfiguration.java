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

package ch.nexsol.gateway.filter.autoconfigure;

import ch.nexsol.gateway.filter.CorrelationIdFilter;
import ch.nexsol.gateway.filter.factory.AuthorizationGatewayFilterFactory;
import ch.nexsol.gateway.filter.factory.ConvertHttpMethodGatewayFilterFactory;
import ch.nexsol.gateway.filter.factory.RecaptchaGatewayFilterFactory;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Auto-configuration registering the gateway filter factories and web filters provided by
 * this module.
 */
@AutoConfiguration
public class FiltersAutoConfiguration {

	/**
	 * Registers the authorization gateway filter factory.
	 * @return the authorization filter factory bean
	 */
	@Bean
	AuthorizationGatewayFilterFactory authorizationGatewayFilterFactory() {
		return new AuthorizationGatewayFilterFactory();
	}

	/**
	 * Registers the HTTP method conversion gateway filter factory.
	 * @return the convert HTTP method filter factory bean
	 */
	@Bean
	ConvertHttpMethodGatewayFilterFactory convertHttpMethodGatewayFilter() {
		return new ConvertHttpMethodGatewayFilterFactory();
	}

	/**
	 * Registers the reCAPTCHA gateway filter factory.
	 * @param webClient the web client used to call the reCAPTCHA endpoint
	 * @return the reCAPTCHA filter factory bean
	 */
	@Bean
	RecaptchaGatewayFilterFactory recaptchaGatewayFilterFactory(WebClient webClient) {
		return new RecaptchaGatewayFilterFactory(webClient);
	}

	/**
	 * Registers the correlation id web filter unless it has been disabled via
	 * configuration.
	 * @return the correlation id web filter bean
	 */
	@Bean
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.webfilter.correlation-id.enabled",
			matchIfMissing = true)
	CorrelationIdFilter correlationIdFilter() {
		return new CorrelationIdFilter();
	}

	/**
	 * Provides a default {@link WebClient} for reCAPTCHA verification when none is
	 * already defined.
	 * @param builder the web client builder
	 * @return the web client bean
	 */
	@Bean
	@ConditionalOnMissingBean(WebClient.class)
	WebClient webClientForRecaptcha(WebClient.Builder builder) {
		return builder.build();
	}

}
