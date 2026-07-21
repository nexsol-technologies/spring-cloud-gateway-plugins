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

package ch.nexsol.gateway.ui.autoconfigure;

import ch.nexsol.gateway.ui.controller.DashboardController;
import ch.nexsol.gateway.ui.controller.GatewayUiModelAttributes;
import ch.nexsol.gateway.ui.metrics.RouteMetricsController;
import ch.nexsol.gateway.ui.metrics.RouteMetricsService;
import ch.nexsol.gateway.ui.nav.GatewayUiMenu;
import ch.nexsol.gateway.ui.nav.NavItem;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration wiring the gateway UI shell: the dashboard controller, the side-menu
 * registry and the built-in home entry.
 * <p>
 * Plugins extend the menu, Spring Boot Admin style, by declaring their own
 * {@link NavItem} beans (typically guarded by {@code @ConditionalOnClass} or
 * {@code @ConditionalOnBean}); {@link GatewayUiMenu} gathers them automatically.
 */
@AutoConfiguration
@Import({ DashboardController.class, GatewayUiModelAttributes.class })
public class GatewayUiAutoConfiguration {

	/**
	 * Registers the side-menu registry aggregating every contributed {@link NavItem}.
	 * @param navItems the provider over every {@link NavItem} bean in the context
	 * @return the menu registry
	 */
	@Bean
	public GatewayUiMenu gatewayUiMenu(ObjectProvider<NavItem> navItems) {
		return new GatewayUiMenu(navItems);
	}

	/**
	 * Contributes the always-present home entry pointing at the shell root.
	 * @return the home menu entry
	 */
	@Bean
	public NavItem homeNavItem() {
		return new NavItem("home", "Home", "icon-home", "/ui", 0);
	}

	/**
	 * Contributes the routes entry, Spring Boot Admin style, only when the
	 * database-backed routes management UI is present on the classpath.
	 * @return the routes menu entry
	 */
	@Bean
	@ConditionalOnClass(name = "ch.nexsol.gateway.database.controller.RouteViewController")
	public NavItem routesNavItem() {
		return new NavItem("routes", "Database routes", "icon-plugin", "/ui/routes/db", 10);
	}

	/**
	 * Activates the traffic bubble chart only when Micrometer is on the classpath: the
	 * view plots the gateway routes from their request metrics read off the meter
	 * registry.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(MeterRegistry.class)
	@Import(RouteMetricsController.class)
	static class RouteMetricsConfiguration {

		/**
		 * Registers the metrics aggregation service.
		 * @param meterRegistry the provider over the application meter registry
		 * @return the metrics service
		 */
		@Bean
		RouteMetricsService routeMetricsService(ObjectProvider<MeterRegistry> meterRegistry) {
			return new RouteMetricsService(meterRegistry);
		}

		/**
		 * Contributes the traffic entry to the side menu.
		 * @return the traffic menu entry
		 */
		@Bean
		NavItem trafficNavItem() {
			return new NavItem("traffic", "Traffic", "icon-chart", "/ui/metrics", 20);
		}

	}

}
