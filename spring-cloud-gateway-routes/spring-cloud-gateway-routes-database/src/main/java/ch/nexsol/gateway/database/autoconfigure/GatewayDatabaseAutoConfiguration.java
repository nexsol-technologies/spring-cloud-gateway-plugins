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

package ch.nexsol.gateway.database.autoconfigure;

import ch.nexsol.gateway.commons.security.SecuredPaths;
import ch.nexsol.gateway.database.RouteManagementPaths;
import ch.nexsol.gateway.database.RoutesDatabaseProperties;
import ch.nexsol.gateway.database.controller.FilterController;
import ch.nexsol.gateway.database.controller.PredicateController;
import ch.nexsol.gateway.database.controller.RouteController;
import ch.nexsol.gateway.database.controller.error.ControllerExceptionHandler;
import ch.nexsol.gateway.database.locator.DatabaseRouteDefinitionLocator;
import ch.nexsol.gateway.database.service.ApiService;
import ch.nexsol.gateway.database.service.ArgumentService;
import ch.nexsol.gateway.database.service.FilterService;
import ch.nexsol.gateway.database.service.GatewayConfigService;
import ch.nexsol.gateway.database.service.PredicateService;
import ch.nexsol.gateway.database.service.RouteService;
import ch.nexsol.gateway.database.webfilter.ReadOnlyRouteManagementWebFilter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * Auto-configuration wiring the database-backed gateway route management: the route
 * definition locator, the services, the R2DBC repositories and entity scanning, and
 * &mdash; as far as {@link RoutesDatabaseProperties.Access the access} allows &mdash; the
 * REST API.
 * <p>
 * The page over these routes belongs to the console, which serves it as it serves every
 * other view: this plugin holds the routes, the console renders them.
 * <p>
 * The locator and the services are wired whatever the access: the routes stored in the
 * database keep feeding the gateway. What the access governs is what is
 * <em>published</em> over HTTP, which is a different decision from where the routes come
 * from. The switch for the other decision is
 * {@code spring.cloud.gateway.server.webflux.routes-database.enabled}, which unwires the
 * plugin altogether &mdash; the source included.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.cloud.gateway.server.webflux.routes-database", name = "enabled",
		matchIfMissing = true)
@EnableConfigurationProperties(RoutesDatabaseProperties.class)
@Import({ DatabaseRouteDefinitionLocator.class, GatewayConfigService.class, ApiService.class, RouteService.class,
		PredicateService.class, FilterService.class, ArgumentService.class })
@EnableR2dbcRepositories(basePackages = "ch.nexsol.gateway.database.repository")
@EntityScan(basePackages = "ch.nexsol.gateway.database.entity")
public class GatewayDatabaseAutoConfiguration {

	/**
	 * Registers the filter refusing the operations that would change the routing table,
	 * when the access is read-only. It answers before anything asks who is calling: an
	 * operation that is not published is not a question of credentials.
	 * @return the read-only filter
	 */
	@Bean
	@Conditional(OnRouteManagementReadOnlyCondition.class)
	@ConditionalOnMissingBean
	public ReadOnlyRouteManagementWebFilter readOnlyRouteManagementWebFilter() {
		return new ReadOnlyRouteManagementWebFilter();
	}

	/**
	 * Registers the REST API, unless the access publishes nothing.
	 */
	@Configuration(proxyBeanMethods = false)
	@Conditional(OnRouteManagementExposedCondition.class)
	@Import({ ControllerExceptionHandler.class, RouteController.class, FilterController.class,
			PredicateController.class })
	static class RouteApiConfiguration {

		/**
		 * Declares the REST API of the plugin to whoever governs the paths of the
		 * gateway.
		 * <p>
		 * These endpoints create, change and delete routes: they are declared as changing
		 * the gateway, which puts them behind an authenticated principal whether or not
		 * the console in front of them is open. A gateway published without a login is a
		 * decision an operator makes; a route management API published without one is
		 * not.
		 * <p>
		 * They are declared as an API rather than as a page: a client calls them with a
		 * token and a JSON body, holding no session and therefore no CSRF token to send
		 * back.
		 * @return the paths of the route management API
		 */
		@Bean
		@ConditionalOnMissingBean(name = "routeApiSecuredPaths")
		SecuredPaths routeApiSecuredPaths() {
			return SecuredPaths.api(RouteManagementPaths.API.toArray(String[]::new));
		}

	}

}
