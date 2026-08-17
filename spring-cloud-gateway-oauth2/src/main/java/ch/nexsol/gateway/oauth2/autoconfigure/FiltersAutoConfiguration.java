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

import ch.nexsol.gateway.oauth2.filter.factory.AuthorizationTokenGatewayFilterFactory;
import ch.nexsol.gateway.oauth2.filter.webfilter.BasicAuthExchangeToAccessTokenGatewayWebFilter;
import ch.nexsol.gateway.oauth2.filter.webfilter.condition.BasicAuthExchangeConfiguredCondition;
import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Auto-configuration registering the OAuth 2.0 gateway filter factories and web filters,
 * including the authorization token filter factory and the Basic-auth to access-token
 * exchange web filter.
 */
@AutoConfiguration
public class FiltersAutoConfiguration {

	/**
	 * Registers the {@code AuthorizationToken} filter factory. It authorizes on the token
	 * the resource server of the application already authenticated, and never verifies
	 * one itself, so it needs nothing injected.
	 * @return the authorization token filter factory bean
	 */
	@Bean
	AuthorizationTokenGatewayFilterFactory authorizationTokenGatewayFilterFactory() {
		return new AuthorizationTokenGatewayFilterFactory();
	}

	@Bean
	@Conditional(BasicAuthExchangeConfiguredCondition.class)
	@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.webfilter.basicauth-exchange-oauth2")
	BasicAuthExchangeToAccessTokenProperties basicAuthForClientCredentialsProperties() {
		return new BasicAuthExchangeToAccessTokenProperties();
	}

	/**
	 * Registers the Basic-auth to access-token exchange web filter. The token cache and
	 * the observation registry are taken from the application when it provides them, and
	 * default to an in-memory cache and to a no-op registry otherwise, so that this
	 * module does not require caching to be enabled in the host application. The web
	 * client is the one the application configured, so the exchange with the
	 * authorization server is instrumented like any other call it makes.
	 * @param properties the exchange configuration
	 * @param cacheManager the optional application cache manager
	 * @param observationRegistry the optional application observation registry
	 * @param webClientBuilder the application web client builder
	 * @return the Basic-auth exchange web filter bean
	 */
	@Bean
	@Conditional(BasicAuthExchangeConfiguredCondition.class)
	BasicAuthExchangeToAccessTokenGatewayWebFilter basicAuthExchangeGatewayWebFilter(
			BasicAuthExchangeToAccessTokenProperties properties, ObjectProvider<CacheManager> cacheManager,
			ObjectProvider<ObservationRegistry> observationRegistry, WebClient.Builder webClientBuilder) {
		return new BasicAuthExchangeToAccessTokenGatewayWebFilter(properties,
				cacheManager.getIfAvailable(ConcurrentMapCacheManager::new),
				observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP), webClientBuilder);
	}

}
