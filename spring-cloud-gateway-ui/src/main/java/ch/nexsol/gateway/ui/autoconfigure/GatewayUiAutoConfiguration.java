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

import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.ui.audit.AuditOverviewContribution;
import ch.nexsol.gateway.ui.audit.AuditTailBeanPostProcessor;
import ch.nexsol.gateway.ui.audit.AuditTailBuffer;
import ch.nexsol.gateway.ui.audit.AuditTailController;
import ch.nexsol.gateway.ui.controller.DashboardController;
import ch.nexsol.gateway.ui.controller.GatewayUiModelAttributes;
import ch.nexsol.gateway.ui.metrics.MetricsOverviewContribution;
import ch.nexsol.gateway.ui.metrics.RouteMetricsController;
import ch.nexsol.gateway.ui.metrics.RouteMetricsService;
import ch.nexsol.gateway.ui.nav.GatewayUiMenu;
import ch.nexsol.gateway.ui.nav.NavItem;
import ch.nexsol.gateway.ui.overview.OverviewContribution;
import ch.nexsol.gateway.ui.overview.OverviewService;
import ch.nexsol.gateway.ui.routes.RouteInventoryController;
import ch.nexsol.gateway.ui.routes.RouteInventoryService;
import ch.nexsol.gateway.ui.routes.RouteOverviewContribution;
import ch.nexsol.gateway.ui.routes.RouteTesterController;
import ch.nexsol.gateway.ui.routes.RouteTesterService;
import ch.nexsol.gateway.ui.security.UiSecuredPaths;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration wiring the gateway UI shell: the dashboard controller, the side-menu
 * registry, the home page overview and the views whose prerequisites are on the
 * classpath.
 * <p>
 * Plugins extend the menu, Spring Boot Admin style, by declaring their own
 * {@link NavItem} beans (typically guarded by {@code @ConditionalOnClass} or
 * {@code @ConditionalOnBean}); {@link GatewayUiMenu} gathers them automatically. The same
 * goes for the home page figures, gathered from every {@link OverviewContribution} bean.
 * <p>
 * Each view is guarded by the presence of the types it is built on, and resolves the
 * beans it reads through an {@link ObjectProvider}: a view whose source of data is absent
 * reports that it has nothing to show rather than breaking the context.
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
	 * Registers the home page aggregation over every contributed figure.
	 * @param contributions the provider over every {@link OverviewContribution} bean
	 * @param applicationContext the context the gateway uptime is read from
	 * @return the overview service
	 */
	@Bean
	public OverviewService overviewService(ObjectProvider<OverviewContribution> contributions,
			ApplicationContext applicationContext) {
		return new OverviewService(contributions, applicationContext);
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
	 * Declares the paths of the shell itself: the home page and the assets every page
	 * loads.
	 * @return the shell paths
	 */
	@Bean
	public UiSecuredPaths shellSecuredPaths() {
		return new UiSecuredPaths("/ui", "/css/bootstrap.min.css", "/css/gateway-ui.css", "/js/htmx.min.js",
				"/js/bootstrap.bundle.min.js", "/js/gateway-ui.js");
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
	 * Activates the routes view whenever the gateway route definition types are on the
	 * classpath: it lists what every source contributed, so the effective route table can
	 * be traced back to the configuration it came from.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RouteDefinitionLocator.class)
	@Import(RouteInventoryController.class)
	static class RouteInventoryConfiguration {

		/**
		 * Registers the service listing the resolved route definitions per source.
		 * @param locators the provider over every route definition source in the context
		 * @param publisher the publisher used to ask the gateway to rebuild its route
		 * table
		 * @return the route inventory service
		 */
		@Bean
		RouteInventoryService routeInventoryService(ObjectProvider<RouteDefinitionLocator> locators,
				ApplicationEventPublisher publisher) {
			return new RouteInventoryService(locators, publisher);
		}

		/**
		 * Contributes the route figures to the home page.
		 * @param inventoryService the route inventory service
		 * @return the route overview contribution
		 */
		@Bean
		RouteOverviewContribution routeOverviewContribution(RouteInventoryService inventoryService) {
			return new RouteOverviewContribution(inventoryService);
		}

		/**
		 * Contributes the routes entry to the side menu.
		 * @return the routes menu entry
		 */
		@Bean
		NavItem routesInventoryNavItem() {
			return new NavItem("routes-all", "Routes", "icon-route", "/ui/routes", 5);
		}

		/**
		 * Declares the paths of the routes view.
		 * @return the routes view paths
		 */
		@Bean
		UiSecuredPaths routeInventorySecuredPaths() {
			return new UiSecuredPaths("/ui/routes", "/ui/routes/list", "/ui/routes/reload", "/js/gateway-routes.js");
		}

	}

	/**
	 * Activates the route tester whenever the gateway route table type is on the
	 * classpath: the view evaluates a described request against the very predicates the
	 * gateway would apply.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RouteLocator.class)
	@Import(RouteTesterController.class)
	static class RouteTesterConfiguration {

		/**
		 * Registers the service evaluating a request against the route table.
		 * @param routeLocator the provider over the locator exposing the effective route
		 * table
		 * @param inventoryService the service resolving the definition behind a route
		 * @param applicationContext the context exposed to the predicates under test
		 * @return the route tester service
		 */
		@Bean
		RouteTesterService routeTesterService(ObjectProvider<RouteLocator> routeLocator,
				RouteInventoryService inventoryService, ApplicationContext applicationContext) {
			return new RouteTesterService(routeLocator, inventoryService, applicationContext);
		}

		/**
		 * Contributes the route tester entry to the side menu.
		 * @return the route tester menu entry
		 */
		@Bean
		NavItem routeTesterNavItem() {
			return new NavItem("route-tester", "Route tester", "icon-target", "/ui/routes/test", 15);
		}

		/**
		 * Declares the paths of the route tester view, served on GET and POST.
		 * @return the route tester paths
		 */
		@Bean
		UiSecuredPaths routeTesterSecuredPaths() {
			return new UiSecuredPaths("/ui/routes/test");
		}

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
		 * Contributes the traffic figures to the home page.
		 * @param metricsService the metrics aggregation service
		 * @return the traffic overview contribution
		 */
		@Bean
		MetricsOverviewContribution metricsOverviewContribution(RouteMetricsService metricsService) {
			return new MetricsOverviewContribution(metricsService);
		}

		/**
		 * Contributes the traffic entry to the side menu.
		 * @return the traffic menu entry
		 */
		@Bean
		NavItem trafficNavItem() {
			return new NavItem("traffic", "Traffic", "icon-chart", "/ui/metrics", 20);
		}

		/**
		 * Declares the paths of the traffic view and of the charting library it loads.
		 * @return the traffic view paths
		 */
		@Bean
		UiSecuredPaths routeMetricsSecuredPaths() {
			return new UiSecuredPaths("/ui/metrics", "/ui/metrics/data", "/js/echarts.min.js", "/js/echarts-gl.min.js",
					"/js/gateway-metrics.js");
		}

	}

	/**
	 * Activates the audit view only when the audit plugin is on the classpath: the view
	 * tails the events on their way to whichever backend the plugin publishes to.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(AuditEventPublisher.class)
	@Import(AuditTailController.class)
	static class AuditTailConfiguration {

		/**
		 * Registers the bounded, in-memory tail of the recent audit events.
		 * @return the audit tail buffer
		 */
		@Bean
		AuditTailBuffer auditTailBuffer() {
			return new AuditTailBuffer();
		}

		/**
		 * Wraps the configured audit publisher so the published events also reach the
		 * tail. Declared {@code static} because a bean post-processor must not force the
		 * enclosing configuration to be created early.
		 * @param buffer the provider over the audit tail buffer
		 * @return the post-processor decorating the audit publisher
		 */
		@Bean
		static BeanPostProcessor auditTailBeanPostProcessor(ObjectProvider<AuditTailBuffer> buffer) {
			return new AuditTailBeanPostProcessor(buffer);
		}

		/**
		 * Contributes the audit figures to the home page.
		 * @param buffer the audit tail buffer
		 * @return the audit overview contribution
		 */
		@Bean
		AuditOverviewContribution auditOverviewContribution(AuditTailBuffer buffer) {
			return new AuditOverviewContribution(buffer);
		}

		/**
		 * Contributes the audit entry to the side menu.
		 * @return the audit menu entry
		 */
		@Bean
		NavItem auditNavItem() {
			return new NavItem("audit", "Audit", "icon-list", "/ui/audit", 30);
		}

		/**
		 * Declares the paths of the audit view.
		 * @return the audit view paths
		 */
		@Bean
		UiSecuredPaths auditTailSecuredPaths() {
			return new UiSecuredPaths("/ui/audit", "/ui/audit/events", "/js/gateway-audit.js");
		}

	}

}
