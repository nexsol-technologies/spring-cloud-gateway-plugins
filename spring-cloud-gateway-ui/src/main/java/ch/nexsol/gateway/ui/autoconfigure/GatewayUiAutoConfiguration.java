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
import ch.nexsol.gateway.commons.security.SecuredPaths;
import ch.nexsol.gateway.commons.security.SecuredPathsContribution;
import ch.nexsol.gateway.database.service.ApiService;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import ch.nexsol.gateway.metrics.autoconfigure.MetricsAutoConfiguration;
import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import ch.nexsol.gateway.ui.audit.AuditExclusionBeanPostProcessor;
import ch.nexsol.gateway.ui.audit.AuditOverviewContribution;
import ch.nexsol.gateway.ui.audit.AuditTailBeanPostProcessor;
import ch.nexsol.gateway.ui.audit.AuditTailBuffer;
import ch.nexsol.gateway.ui.audit.AuditTailController;
import ch.nexsol.gateway.ui.controller.DashboardController;
import ch.nexsol.gateway.ui.controller.GatewayUiModelAttributes;
import ch.nexsol.gateway.ui.metrics.InstanceMetricsController;
import ch.nexsol.gateway.ui.metrics.InstancesOverviewContribution;
import ch.nexsol.gateway.ui.metrics.MetricsOverviewContribution;
import ch.nexsol.gateway.ui.metrics.PoolRouteResolver;
import ch.nexsol.gateway.ui.metrics.RouteMetricsController;
import ch.nexsol.gateway.ui.nav.GatewayUiMenu;
import ch.nexsol.gateway.ui.nav.NavItem;
import ch.nexsol.gateway.ui.openapi.OpenapiViewController;
import ch.nexsol.gateway.ui.openapi.OpenapiViewProperties;
import ch.nexsol.gateway.ui.overview.OverviewContribution;
import ch.nexsol.gateway.ui.overview.OverviewService;
import ch.nexsol.gateway.ui.routes.DatabaseRoutesController;
import ch.nexsol.gateway.ui.routes.RouteInventoryController;
import ch.nexsol.gateway.ui.routes.RouteInventoryService;
import ch.nexsol.gateway.ui.routes.RouteOverviewContribution;
import ch.nexsol.gateway.ui.routes.RouteTesterController;
import ch.nexsol.gateway.ui.routes.RouteTesterService;
import ch.nexsol.gateway.ui.security.UiSecuredPaths;
import ch.nexsol.gateway.ui.servicegraph.ServiceGraphController;
import ch.nexsol.gateway.ui.servicegraph.ServiceGraphOverviewContribution;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
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
@AutoConfiguration(after = MetricsAutoConfiguration.class,
		afterName = "ch.nexsol.gateway.database.autoconfigure.GatewayDatabaseAutoConfiguration")
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
	 * Declares the path of the shell itself, the one every view hangs under.
	 * @return the shell path
	 */
	@Bean
	public UiSecuredPaths shellSecuredPaths() {
		return new UiSecuredPaths("/ui");
	}

	/**
	 * Declares the assets every page loads, the login page included. They stay reachable
	 * without a principal whatever the console does: a login page painted with assets
	 * that are themselves behind the login has nothing to paint with.
	 * @return the shell asset paths
	 */
	@Bean
	public SecuredPaths shellAssetPaths() {
		// The minified Bootstrap files carry a sourceMappingURL, so a browser with its
		// developer tools open requests maps this module does not ship. Declared so those
		// requests answer as the 404 they are, and stay out of the audit trail.
		return SecuredPaths.open("/css/bootstrap.min.css", "/css/bootstrap.min.css.map", "/css/gateway-ui.css",
				"/js/htmx.min.js", "/js/bootstrap.bundle.min.js", "/js/bootstrap.bundle.min.js.map",
				"/js/gateway-ui.js", "/img/logo.png", "/img/logo-dark.png", "/img/icon.png");
	}

	/**
	 * Activates the database routes view when the routes-database plugin is on the
	 * classpath and publishes something: the view renders the routes that plugin holds,
	 * as the traffic view renders the figures of the metrics plugin.
	 * <p>
	 * The view hangs on the declaration the plugin publishes when it publishes its routes
	 * at all, rather than on its {@code access} property: the plugin is the one deciding,
	 * and a view over routes it does not publish would be a view over nothing. The bean
	 * is named rather than typed on purpose &mdash; naming a condition class of an
	 * optional dependency would have this console fail to start without it, since Spring
	 * has to load such a class to evaluate it.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(ApiService.class)
	@ConditionalOnBean(name = "routeApiSecuredPaths")
	@Import(DatabaseRoutesController.class)
	static class DatabaseRoutesViewConfiguration {

		/**
		 * Contributes the routes entry, Spring Boot Admin style.
		 * @return the database routes menu entry
		 */
		@Bean
		NavItem routesNavItem() {
			return new NavItem("routes", "Database routes", "icon-plugin", "/ui/routes/db", 10);
		}

		/**
		 * Declares the paths of the view. They create and delete routes, so they are
		 * declared as changing the gateway rather than as a view following the mode: a
		 * console published without a login is a decision, a page that reconfigures the
		 * routing table without one is an accident.
		 * <p>
		 * They keep the CSRF protection of the console: the form and the HTMX fragments
		 * carry the token it publishes.
		 * @return the paths of the database routes view
		 */
		@Bean
		SecuredPaths routeViewSecuredPaths() {
			return SecuredPaths.write("/ui/routes/db", "/ui/routes/db/list", "/ui/routes/db/new",
					"/ui/routes/db/predicate-row", "/ui/routes/db/filter-row",
					"/ui/routes/db/element-args/{kind}/{index}", "/ui/routes/db/{id}", "/ui/routes/db/{id}/edit");
		}

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
	 * Activates the traffic bubble chart when the metrics plugin is active: the view
	 * plots the gateway routes from whatever {@link RouteMetricsSource} that plugin
	 * resolved &mdash; the local meter registry by default, a consolidated figure when a
	 * provider module is on the classpath.
	 * <p>
	 * The conditions mirror those of {@code MetricsAutoConfiguration} rather than testing
	 * for the source bean itself: {@code @ConditionalOnBean} depends on the order the
	 * configurations are applied in, which does not hold when this class is reached by a
	 * component scan instead of the auto-configuration import.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(MeterRegistry.class)
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.metrics.enabled", matchIfMissing = true)
	@Import(RouteMetricsController.class)
	static class RouteMetricsConfiguration {

		/**
		 * Contributes the traffic figures to the home page.
		 * @param metricsSource the provider over the active metrics source
		 * @return the traffic overview contribution
		 */
		@Bean
		MetricsOverviewContribution metricsOverviewContribution(ObjectProvider<RouteMetricsSource> metricsSource) {
			return new MetricsOverviewContribution(metricsSource);
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
			return new UiSecuredPaths("/ui/metrics", "/ui/metrics/data", "/js/echarts.min.js",
					"/js/gateway-metrics.js");
		}

		/**
		 * Activates the instances view, which reads a source of its own: the technical
		 * health of each instance rather than the traffic of each route.
		 * <p>
		 * Nested inside the traffic configuration so it inherits its conditions and only
		 * adds its own switch, {@code @ConditionalOnProperty} not being repeatable.
		 */
		@Configuration(proxyBeanMethods = false)
		@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.metrics.instance.enabled",
				matchIfMissing = true)
		@Import(InstanceMetricsController.class)
		static class InstanceMetricsConfiguration {

			/**
			 * Names the routes a connection pool serves, from the route table and, for a
			 * load-balanced route, from the service registry.
			 * <p>
			 * Declared on the route table being exposed at all: without it a pool can
			 * only be shown as the container address it connects to, which is what the
			 * view falls back to.
			 * @param inventoryService the inventory the route table is read from
			 * @param discoveryClient the provider over the service registry
			 * @return the pool route resolver
			 */
			@Bean
			@ConditionalOnClass({ RouteDefinitionLocator.class, ReactiveDiscoveryClient.class })
			PoolRouteResolver poolRouteResolver(RouteInventoryService inventoryService,
					ObjectProvider<ReactiveDiscoveryClient> discoveryClient) {
				return new PoolRouteResolver(inventoryService, discoveryClient);
			}

			/**
			 * Contributes the instance count to the home page.
			 * @param instanceMetricsSource the provider over the active instance source
			 * @return the instances overview contribution
			 */
			@Bean
			InstancesOverviewContribution instancesOverviewContribution(
					ObjectProvider<InstanceMetricsSource> instanceMetricsSource) {
				return new InstancesOverviewContribution(instanceMetricsSource);
			}

			/**
			 * Contributes the runtime entry to the side menu, right after the traffic
			 * one: the same figures seen per instance rather than per route.
			 * @return the runtime menu entry
			 */
			@Bean
			NavItem instancesNavItem() {
				return new NavItem("instances", "Runtime", "icon-server", "/ui/metrics/instances", 21);
			}

			/**
			 * Declares the paths of the instances view.
			 * @return the instances view paths
			 */
			@Bean
			UiSecuredPaths instanceMetricsSecuredPaths() {
				return new UiSecuredPaths("/ui/metrics/instances", "/ui/metrics/instances/data",
						"/js/gateway-instances.js");
			}

		}

	}

	/**
	 * Activates the service graph view when the service graph plugin is on the classpath
	 * and enabled: the view draws whichever {@code ServiceGraphSource} that plugin
	 * resolved &mdash; the calls this instance counted by default, a consolidated graph
	 * when a provider module is added, the graph of a tracing backend when one is
	 * configured.
	 * <p>
	 * The conditions mirror those of the plugin's own auto-configuration rather than
	 * testing for the source bean, for the same reason the traffic view does:
	 * {@code @ConditionalOnBean} depends on the order configurations are applied in,
	 * which does not hold under a component scan.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(ServiceGraphSource.class)
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.service-graph.enabled", matchIfMissing = true)
	@Import(ServiceGraphController.class)
	static class ServiceGraphConfiguration {

		/**
		 * Contributes the size of the graph to the home page.
		 * @param graphSource the provider over the active graph source
		 * @return the service graph overview contribution
		 */
		@Bean
		ServiceGraphOverviewContribution serviceGraphOverviewContribution(
				ObjectProvider<ServiceGraphSource> graphSource) {
			return new ServiceGraphOverviewContribution(graphSource);
		}

		/**
		 * Contributes the service graph entry to the side menu, next to the traffic and
		 * instances entries: the same traffic seen as a graph rather than as figures.
		 * @return the service graph menu entry
		 */
		@Bean
		NavItem serviceGraphNavItem() {
			return new NavItem("service-graph", "Service graph", "icon-graph", "/ui/service-graph", 22);
		}

		/**
		 * Declares the paths of the service graph view and of the charting library it
		 * loads, so the console governs them like its own.
		 * @return the service graph view paths
		 */
		@Bean
		UiSecuredPaths serviceGraphSecuredPaths() {
			return new UiSecuredPaths("/ui/service-graph", "/ui/service-graph/data", "/js/echarts.min.js",
					"/js/gateway-service-graph.js");
		}

	}

	/**
	 * Activates the OpenAPI view only when the OpenAPI hub plugin is on the classpath and
	 * enabled: the view renders the contracts it aggregates, read from the SpringDoc
	 * endpoints the hub keeps in sync.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "ch.nexsol.gateway.openapi.hub.SpringDocOpenapiRoutes")
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.hub-openapi.enabled", havingValue = "true")
	@EnableConfigurationProperties(OpenapiViewProperties.class)
	@Import(OpenapiViewController.class)
	static class OpenapiViewConfiguration {

		/**
		 * Contributes the OpenAPI entry to the side menu.
		 * @return the OpenAPI menu entry
		 */
		@Bean
		NavItem openapiNavItem() {
			return new NavItem("openapi", "OpenAPI", "icon-book", "/ui/openapi", 25);
		}

		/**
		 * Declares the paths of the OpenAPI view. The contracts themselves are served by
		 * the hub, which declares them in its own chain.
		 * @return the OpenAPI view paths
		 */
		@Bean
		UiSecuredPaths openapiSecuredPaths() {
			return new UiSecuredPaths("/ui/openapi", "/js/scalar.standalone.js", "/js/gateway-openapi.js");
		}

	}

	/**
	 * Activates the audit view only when the audit plugin is on the classpath and
	 * enabled: the view tails the events on their way to whichever backend the plugin
	 * publishes to. The property condition mirrors the one guarding the plugin itself, so
	 * a gateway with auditing turned off is not offered a view over events that are never
	 * published.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(AuditEventPublisher.class)
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.audit.enabled", matchIfMissing = true)
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
		 * Keeps the traffic of the console out of the audit trail, by excluding the paths
		 * the active views serve from the global auditing web filter. Declared
		 * {@code static} because a bean post-processor must not force the enclosing
		 * configuration to be created early.
		 * @param securedPaths the provider over the paths contributed by the active views
		 * and by the plugins the console governs
		 * @return the post-processor excluding the console paths
		 */
		@Bean
		static BeanPostProcessor auditExclusionBeanPostProcessor(
				ObjectProvider<SecuredPathsContribution> securedPaths) {
			return new AuditExclusionBeanPostProcessor(securedPaths);
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
