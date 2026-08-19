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
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.cache.CacheManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Auto-configuration tests for {@link FiltersAutoConfiguration}, checking that the
 * Basic-auth exchange filter stands alone when the application provides neither a cache
 * manager nor an observation registry.
 */
class FiltersAutoConfigurationTests {

	private static final String TOKEN_URIS = "spring.cloud.gateway.server.webflux.webfilter."
			+ "basicauth-exchange-oauth2.token-uris.alice=http://auth.example/token";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(WebClientAutoConfiguration.class, FiltersAutoConfiguration.class));

	@Test
	void authorizationTokenFilterFactoryIsAlwaysRegistered() {
		this.runner.run((context) -> {
			assertThat(context).hasSingleBean(AuthorizationTokenGatewayFilterFactory.class);
			assertThat(context).doesNotHaveBean(BasicAuthExchangeToAccessTokenGatewayWebFilter.class);
		});
	}

	@Test
	void basicAuthExchangeFilterIsRegisteredWithoutCacheManagerBean() {
		this.runner.withPropertyValues(TOKEN_URIS).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(BasicAuthExchangeToAccessTokenGatewayWebFilter.class);
		});
	}

	@Test
	void applicationCacheManagerIsUsedWhenPresent() {
		CacheManager cacheManager = mock(CacheManager.class);
		this.runner.withPropertyValues(TOKEN_URIS).withBean(CacheManager.class, () -> cacheManager).run((context) -> {
			assertThat(context).hasSingleBean(BasicAuthExchangeToAccessTokenGatewayWebFilter.class);
			verify(cacheManager).getCache("basicauth-token-exchange.cache");
		});
	}

}
