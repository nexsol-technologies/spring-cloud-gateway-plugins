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

package ch.nexsol.gateway.routes.configserver.autoconfigure;

import ch.nexsol.gateway.routes.configserver.ConfigServerRouteDefinitionLoader;
import ch.nexsol.gateway.routes.configserver.ConfigServerRouteDefinitionLocator;
import ch.nexsol.gateway.routes.configserver.RouteConfigServerLifecycle;
import ch.nexsol.gateway.routes.configserver.RoutesConfigServerProperties;
import ch.nexsol.gateway.routes.files.RouteDefinitionFileParser;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Auto-configuration wiring the Config Server-based route definition locator when
 * {@code spring.cloud.gateway.server.webflux.routes-configserver.enabled} is
 * {@code true}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.cloud.gateway.server.webflux.routes-configserver", name = "enabled",
		havingValue = "true")
@EnableConfigurationProperties(RoutesConfigServerProperties.class)
public class RoutesConfigServerAutoConfiguration {

	/**
	 * Registers the JSON/YAML route file parser unless the application provides its own.
	 * @return the parser bean
	 */
	@Bean
	@ConditionalOnMissingBean
	RouteDefinitionFileParser routeDefinitionFileParser() {
		return new RouteDefinitionFileParser();
	}

	/**
	 * Registers the loader fetching the configured route files. The client is derived
	 * from the application {@link WebClient.Builder} when one is available.
	 * @param parser the route file parser
	 * @param properties the Config Server locator properties
	 * @param webClientBuilder the optional application web client builder
	 * @return the loader bean
	 */
	@Bean
	ConfigServerRouteDefinitionLoader configServerRouteDefinitionLoader(RouteDefinitionFileParser parser,
			RoutesConfigServerProperties properties, ObjectProvider<WebClient.Builder> webClientBuilder) {
		WebClient webClient = webClientBuilder.getIfAvailable(WebClient::builder).build();
		return new ConfigServerRouteDefinitionLoader(webClient, parser, properties);
	}

	/**
	 * Registers the Config Server-backed route definition locator.
	 * @param loader the Config Server loader
	 * @param publisher the application event publisher
	 * @return the locator bean
	 */
	@Bean
	ConfigServerRouteDefinitionLocator configServerRouteDefinitionLocator(ConfigServerRouteDefinitionLoader loader,
			ApplicationEventPublisher publisher) {
		return new ConfigServerRouteDefinitionLocator(loader, publisher);
	}

	/**
	 * Registers the lifecycle performing the initial fetch and optional periodic reload.
	 * @param locator the locator to refresh
	 * @param properties the Config Server locator properties
	 * @return the lifecycle bean
	 */
	@Bean
	RouteConfigServerLifecycle routeConfigServerLifecycle(ConfigServerRouteDefinitionLocator locator,
			RoutesConfigServerProperties properties) {
		return new RouteConfigServerLifecycle(locator, properties.getUpdateInterval());
	}

}
