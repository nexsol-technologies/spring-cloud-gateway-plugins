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

import ch.nexsol.gateway.openapi.hub.OpenapiService;
import ch.nexsol.gateway.openapi.hub.SpringDocOpenapiRoutes;
import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import reactor.core.publisher.Flux;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.factory.rewrite.GzipMessageBodyResolver;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Auto-configuration tests for {@link HubApiAutoConfiguration}, checking that the hub
 * keeps serving the statically configured contracts when the application has no discovery
 * client.
 */
class HubApiAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HubApiAutoConfiguration.class))
		.withUserConfiguration(GatewayScaffoldingConfiguration.class)
		.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.gateway-uri=http://gateway.example");

	@Test
	void nothingIsRegisteredWhenTheHubIsDisabled() {
		this.runner.run((context) -> assertThat(context).doesNotHaveBean(SpringDocOpenapiRoutes.class));
	}

	@Test
	void discoveryBeansBackOffWithoutDiscoveryClient() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(SpringDocOpenapiRoutes.class);
				assertThat(context).doesNotHaveBean(OpenapiService.class);
				assertThat(context).doesNotHaveBean(HubDiscoveryRouteLocator.class);
			});
	}

	@Test
	void discoveryBeansAreRegisteredWithDiscoveryClient() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true")
			.withUserConfiguration(DiscoveryScaffoldingConfiguration.class)
			.run((context) -> {
				assertThat(context).hasSingleBean(OpenapiService.class);
				assertThat(context).hasSingleBean(HubDiscoveryRouteLocator.class);
			});
	}

	/**
	 * The gateway beans the hub is built on, which the runner does not auto-configure.
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
		ServerCodecConfigurer serverCodecConfigurer() {
			return ServerCodecConfigurer.create();
		}

		@Bean
		GzipMessageBodyResolver gzipMessageBodyResolver() {
			return new GzipMessageBodyResolver();
		}

	}

	/**
	 * The discovery beans an application running with a service registry would provide.
	 */
	@Configuration(proxyBeanMethods = false)
	static class DiscoveryScaffoldingConfiguration {

		@Bean
		ReactiveDiscoveryClient reactiveDiscoveryClient() {
			ReactiveDiscoveryClient discoveryClient = mock(ReactiveDiscoveryClient.class);
			given(discoveryClient.getServices()).willReturn(Flux.empty());
			return discoveryClient;
		}

		@Bean
		DiscoveryLocatorProperties discoveryLocatorProperties() {
			return new DiscoveryLocatorProperties();
		}

	}

}
