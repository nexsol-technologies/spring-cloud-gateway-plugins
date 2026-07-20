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

package ch.nexsol.gateway.routes.openapi.autoconfigure;

import ch.nexsol.gateway.routes.openapi.DefaultOpenApiSpecLoader;
import ch.nexsol.gateway.routes.openapi.OpenApiRouteDefinitionLoader;
import ch.nexsol.gateway.routes.openapi.OpenApiRouteDefinitionLocator;
import ch.nexsol.gateway.routes.openapi.OpenApiRouteDefinitionMapper;
import ch.nexsol.gateway.routes.openapi.OpenApiSpecLoader;
import ch.nexsol.gateway.routes.openapi.RouteOpenapiLifecycle;
import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration wiring the OpenAPI-based route definition locator when
 * {@code spring.cloud.gateway.routes.openapi.enabled} is {@code true}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.cloud.gateway.routes.openapi", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RoutesOpenapiProperties.class)
public class RoutesOpenapiAutoConfiguration {

	/**
	 * Registers the OpenAPI document reader unless the application provides its own.
	 * @return the spec loader bean
	 */
	@Bean
	@ConditionalOnMissingBean
	OpenApiSpecLoader openApiSpecLoader() {
		return new DefaultOpenApiSpecLoader();
	}

	/**
	 * Registers the document-to-routes mapper.
	 * @return the mapper bean
	 */
	@Bean
	@ConditionalOnMissingBean
	OpenApiRouteDefinitionMapper openApiRouteDefinitionMapper() {
		return new OpenApiRouteDefinitionMapper();
	}

	/**
	 * Registers the loader aggregating routes from the configured sources.
	 * @param specLoader the OpenAPI document reader
	 * @param mapper the document-to-routes mapper
	 * @param properties the OpenAPI locator properties
	 * @return the loader bean
	 */
	@Bean
	OpenApiRouteDefinitionLoader openApiRouteDefinitionLoader(OpenApiSpecLoader specLoader,
			OpenApiRouteDefinitionMapper mapper, RoutesOpenapiProperties properties) {
		return new OpenApiRouteDefinitionLoader(specLoader, mapper, properties.getSources());
	}

	/**
	 * Registers the OpenAPI-backed route definition locator.
	 * @param loader the OpenAPI loader
	 * @param publisher the application event publisher
	 * @return the locator bean
	 */
	@Bean
	OpenApiRouteDefinitionLocator openApiRouteDefinitionLocator(OpenApiRouteDefinitionLoader loader,
			ApplicationEventPublisher publisher) {
		return new OpenApiRouteDefinitionLocator(loader, publisher);
	}

	/**
	 * Registers the lifecycle performing the initial generation and optional periodic
	 * reload.
	 * @param locator the locator to refresh
	 * @param properties the OpenAPI locator properties
	 * @return the lifecycle bean
	 */
	@Bean
	RouteOpenapiLifecycle routeOpenapiLifecycle(OpenApiRouteDefinitionLocator locator,
			RoutesOpenapiProperties properties) {
		return new RouteOpenapiLifecycle(locator, properties.getUpdateInterval());
	}

}
