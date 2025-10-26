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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

@AutoConfiguration
public class FiltersAutoConfiguration {

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

	@Bean
	@Conditional(BasicAuthExchangeConfiguredCondition.class)
	BasicAuthExchangeToAccessTokenGatewayWebFilter basicAuthExchangeGatewayWebFilter(
			BasicAuthExchangeToAccessTokenProperties properties, CacheManager cacheManager,
			ObservationRegistry registry) {
		return new BasicAuthExchangeToAccessTokenGatewayWebFilter(properties, cacheManager, registry);
	}

}
