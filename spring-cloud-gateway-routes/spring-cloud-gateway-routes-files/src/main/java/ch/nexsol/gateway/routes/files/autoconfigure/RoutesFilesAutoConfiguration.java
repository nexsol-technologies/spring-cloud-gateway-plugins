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

package ch.nexsol.gateway.routes.files.autoconfigure;

import ch.nexsol.gateway.routes.files.FileRouteDefinitionLoader;
import ch.nexsol.gateway.routes.files.FileRouteDefinitionLocator;
import ch.nexsol.gateway.routes.files.RouteDefinitionFileParser;
import ch.nexsol.gateway.routes.files.RouteFilesLifecycle;
import ch.nexsol.gateway.routes.files.RoutesFilesProperties;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Auto-configuration wiring the file-based route definition locator when
 * {@code spring.cloud.gateway.server.webflux.routes-files.enabled} is {@code true}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.cloud.gateway.server.webflux.routes-files", name = "enabled",
		havingValue = "true")
@EnableConfigurationProperties(RoutesFilesProperties.class)
public class RoutesFilesAutoConfiguration {

	/**
	 * Registers the single-file parser.
	 * @return the parser bean
	 */
	@Bean
	@ConditionalOnMissingBean
	RouteDefinitionFileParser routeDefinitionFileParser() {
		return new RouteDefinitionFileParser();
	}

	/**
	 * Registers the loader aggregating the configured files.
	 * @param parser the single-file parser
	 * @param properties the file locator properties
	 * @param resolver the resource pattern resolver
	 * @return the loader bean
	 */
	@Bean
	FileRouteDefinitionLoader fileRouteDefinitionLoader(RouteDefinitionFileParser parser,
			RoutesFilesProperties properties, ResourcePatternResolver resolver) {
		return new FileRouteDefinitionLoader(parser, properties.getLocations(), resolver);
	}

	/**
	 * Registers the file-backed route definition locator.
	 * @param loader the file loader
	 * @param publisher the application event publisher
	 * @return the locator bean
	 */
	@Bean
	FileRouteDefinitionLocator fileRouteDefinitionLocator(FileRouteDefinitionLoader loader,
			ApplicationEventPublisher publisher) {
		return new FileRouteDefinitionLocator(loader, publisher);
	}

	/**
	 * Registers the lifecycle performing the initial load and optional file watching.
	 * @param locator the locator to refresh
	 * @param loader the loader providing the directories to watch
	 * @param properties the file locator properties
	 * @return the lifecycle bean
	 */
	@Bean
	RouteFilesLifecycle routeFilesLifecycle(FileRouteDefinitionLocator locator, FileRouteDefinitionLoader loader,
			RoutesFilesProperties properties) {
		return new RouteFilesLifecycle(locator, loader, properties.isWatch());
	}

}
