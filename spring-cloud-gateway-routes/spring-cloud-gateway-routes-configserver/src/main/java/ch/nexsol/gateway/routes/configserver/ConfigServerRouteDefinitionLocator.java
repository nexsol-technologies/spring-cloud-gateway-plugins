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

package ch.nexsol.gateway.routes.configserver;

import ch.nexsol.gateway.routes.core.AbstractRefreshableRouteDefinitionLocator;
import reactor.core.publisher.Flux;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link org.springframework.cloud.gateway.route.RouteDefinitionLocator} sourcing route
 * definitions from JSON/YAML files served by a Spring Cloud Config Server. The fetch is
 * fully non-blocking and the result is cached by the superclass.
 */
public class ConfigServerRouteDefinitionLocator extends AbstractRefreshableRouteDefinitionLocator {

	private final ConfigServerRouteDefinitionLoader loader;

	/**
	 * Creates the locator.
	 * @param loader the loader fetching the configured files
	 * @param publisher the publisher used to trigger route refresh events
	 */
	public ConfigServerRouteDefinitionLocator(ConfigServerRouteDefinitionLoader loader,
			ApplicationEventPublisher publisher) {
		super(publisher);
		this.loader = loader;
	}

	@Override
	protected Flux<RouteDefinition> loadRouteDefinitions() {
		return this.loader.load();
	}

}
