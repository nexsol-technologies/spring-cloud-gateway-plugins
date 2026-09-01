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
import ch.nexsol.gateway.filter.IdentityPropagationFilter;
import ch.nexsol.gateway.filter.IdentityPropagationProperties;
import ch.nexsol.gateway.filter.factory.AuthorizationGatewayFilterFactory;
import ch.nexsol.gateway.filter.factory.ConvertHttpMethodGatewayFilterFactory;
import ch.nexsol.gateway.filter.factory.MaintenanceGatewayFilterFactory;
import ch.nexsol.gateway.filter.factory.RecaptchaGatewayFilterFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
	 * Registers the maintenance gateway filter factory.
	 * @return the maintenance filter factory bean
	 */
	@Bean
	MaintenanceGatewayFilterFactory maintenanceGatewayFilterFactory() {
		return new MaintenanceGatewayFilterFactory();
	}

	/**
	 * Registers the reCAPTCHA gateway filter factory.
	 * @param recaptchaWebClient the client dedicated to the reCAPTCHA endpoint
	 * @return the reCAPTCHA filter factory bean
	 */
	@Bean
	RecaptchaGatewayFilterFactory recaptchaGatewayFilterFactory(
			@Qualifier("recaptchaWebClient") WebClient recaptchaWebClient) {
		return new RecaptchaGatewayFilterFactory(recaptchaWebClient);
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
	 * Binds the identity propagation configuration.
	 * @return the identity propagation properties bean
	 */
	@Bean
	@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.identity-propagation")
	IdentityPropagationProperties identityPropagationProperties() {
		return new IdentityPropagationProperties();
	}

	/**
	 * Registers the identity propagation filter when it has been asked for.
	 * <p>
	 * Off unless enabled: it rewrites headers on every routed request, and a gateway
	 * already forwarding headers of these names would see them replaced.
	 * @param properties the identity propagation configuration
	 * @return the identity propagation filter bean
	 */
	@Bean
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.identity-propagation.enabled",
			havingValue = "true")
	IdentityPropagationFilter identityPropagationFilter(IdentityPropagationProperties properties) {
		return new IdentityPropagationFilter(properties);
	}

	/**
	 * Registers the client the reCAPTCHA filter verifies tokens with.
	 * <p>
	 * Declared as its own bean, and injected by name, so the filter never borrows the
	 * {@code WebClient} of the host application: that one may carry a base URL, an
	 * {@code Authorization} header the application's own credentials, or be
	 * {@code @LoadBalanced} &mdash; which would send the application's credentials to
	 * Google, or resolve the verification host through the service registry and fail. An
	 * application needing more than the defaults declares a {@code recaptchaWebClient}
	 * bean of its own and it is used instead.
	 * <p>
	 * It is derived from the application {@code WebClient.Builder} when there is one, so
	 * the codecs and customizers of the application still apply, and falls back to a
	 * plain builder so this module stays usable without the WebClient auto-configuration
	 * on the classpath.
	 * @param webClientBuilder the optional application web client builder
	 * @return the client dedicated to the reCAPTCHA endpoint
	 */
	@Bean
	@ConditionalOnMissingBean(name = "recaptchaWebClient")
	WebClient recaptchaWebClient(ObjectProvider<WebClient.Builder> webClientBuilder) {
		return webClientBuilder.getIfAvailable(WebClient::builder).build();
	}

}
