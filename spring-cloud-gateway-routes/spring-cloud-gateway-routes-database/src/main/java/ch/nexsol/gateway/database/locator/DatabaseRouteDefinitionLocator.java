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

package ch.nexsol.gateway.database.locator;

import java.time.Duration;

import ch.nexsol.gateway.database.RoutesDatabaseProperties;
import ch.nexsol.gateway.database.service.RouteService;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;

/**
 * {@link RouteDefinitionLocator} that sources Spring Cloud Gateway route definitions from
 * the routes persisted in the database.
 */
@Component
public class DatabaseRouteDefinitionLocator implements RouteDefinitionLocator {

	private final RouteService routeService;

	private final Duration loadTimeout;

	/**
	 * Creates the locator with the route service supplying the definitions, giving the
	 * database the default time to answer.
	 * @param routeService the route service
	 */
	public DatabaseRouteDefinitionLocator(RouteService routeService) {
		this(routeService, new RoutesDatabaseProperties());
	}

	/**
	 * Creates the locator with the route service supplying the definitions.
	 * @param routeService the route service
	 * @param properties the plugin configuration, holding the time the database is given
	 */
	@Autowired
	public DatabaseRouteDefinitionLocator(RouteService routeService, RoutesDatabaseProperties properties) {
		this.routeService = routeService;
		this.loadTimeout = properties.getLoadTimeout();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Flux<RouteDefinition> getRouteDefinitions() {
		// Every refresh of the route table reads the database: one that stops answering
		// would hold each of them open, the connections they wait for piling up.
		return this.routeService.loadSpringCloudGatewayRouteDefinition().timeout(this.loadTimeout);
	}

}
