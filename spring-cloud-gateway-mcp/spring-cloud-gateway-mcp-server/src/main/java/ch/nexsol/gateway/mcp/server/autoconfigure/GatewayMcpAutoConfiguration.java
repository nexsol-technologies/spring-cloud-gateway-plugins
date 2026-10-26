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

package ch.nexsol.gateway.mcp.server.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import ch.nexsol.gateway.commons.routes.RouteTester;
import ch.nexsol.gateway.commons.security.SecuredPaths;
import ch.nexsol.gateway.mcp.server.GatewayMcpProperties;
import ch.nexsol.gateway.mcp.server.WriteGuard;
import ch.nexsol.gateway.mcp.server.metrics.MetricsTools;
import ch.nexsol.gateway.mcp.server.pentest.PentestTools;
import ch.nexsol.gateway.mcp.server.pentest.PentestWriteTools;
import ch.nexsol.gateway.mcp.server.prompts.GatewayPrompts;
import ch.nexsol.gateway.mcp.server.resources.GatewayResources;
import ch.nexsol.gateway.mcp.server.routes.RouteMatchTools;
import ch.nexsol.gateway.mcp.server.routes.RouteTools;
import ch.nexsol.gateway.mcp.server.routes.RouteWriteTools;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import ch.nexsol.gateway.pentest.core.report.ReportService;
import ch.nexsol.gateway.pentest.core.store.FindingStore;
import ch.nexsol.gateway.pentest.passive.score.RouteScoreService;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerSseProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration registering what the MCP server serves: the tools over the routes,
 * the prompts, and — when the plugin behind them is wired — the tools over the metrics
 * and the passive scan findings. Each is a plain bean carrying {@code @McpTool} or
 * {@code @McpPrompt} methods, which Spring AI's annotation scanner turns into the
 * specifications of the server. Active only when
 * {@code spring.cloud.gateway.server.webflux.mcp.enabled} is {@code true}.
 * <p>
 * Ordered ahead of the Spring AI server auto-configurations on purpose, the stateless
 * ones and the stateful ones alike since the application may switch protocol: the scanner
 * registers a bean when that bean is initialised, and the server reads the registry once,
 * when it is built. A tool bean created after the server is never listed.
 */
@AutoConfiguration(
		before = { McpServerStatelessAutoConfiguration.class,
				StatelessServerSpecificationFactoryAutoConfiguration.class, McpServerAutoConfiguration.class,
				McpServerSpecificationFactoryAutoConfiguration.class },
		afterName = { "ch.nexsol.gateway.metrics.autoconfigure.MetricsAutoConfiguration",
				"ch.nexsol.gateway.pentest.passive.autoconfigure.PassiveScanAutoConfiguration",
				"ch.nexsol.gateway.ui.autoconfigure.GatewayUiAutoConfiguration" })
@ConditionalOnClass(McpTool.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.mcp.enabled", havingValue = "true")
public class GatewayMcpAutoConfiguration {

	@Bean
	@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.mcp")
	GatewayMcpProperties gatewayMcpProperties() {
		return new GatewayMcpProperties();
	}

	@Bean
	RouteTools gatewayMcpRouteTools(ObjectProvider<RouteDefinitionLocator> locators,
			ObjectProvider<GatewayFilterFactory<?>> filterFactories,
			ObjectProvider<RoutePredicateFactory<?>> predicateFactories) {
		return new RouteTools(locators, filterFactories, predicateFactories);
	}

	/**
	 * The route tester, when the console has not already put its own in the context: the
	 * console's reads its cached inventory, this one the route sources.
	 * @param routeLocator the route table
	 * @param locators the route definition sources
	 * @param applicationContext the context exposed to the predicates under test
	 * @return the tester
	 */
	@Bean
	@ConditionalOnMissingBean(RouteTester.class)
	RouteTester gatewayMcpRouteTester(ObjectProvider<RouteLocator> routeLocator,
			ObjectProvider<RouteDefinitionLocator> locators, ApplicationContext applicationContext) {
		return new RouteTester(routeLocator, () -> RouteMatchTools.origins(locators), applicationContext);
	}

	@Bean
	RouteMatchTools gatewayMcpRouteMatchTools(RouteTester tester) {
		return new RouteMatchTools(tester);
	}

	@Bean
	GatewayResources gatewayMcpResources(RouteTools routes) {
		return new GatewayResources(routes);
	}

	@Bean
	GatewayPrompts gatewayMcpPrompts() {
		return new GatewayPrompts();
	}

	/**
	 * Declares the endpoints of the server as secured, whatever protocol is in force. The
	 * properties bean of a protocol exists only when its auto-configuration ran, so these
	 * are the endpoints the server listens on, read the way it reads them: a
	 * {@code @Value} on one key would miss a relaxed spelling, and the SSE endpoints.
	 * @param streamable the endpoint of the stateless and streamable protocols
	 * @param sse the two endpoints of the SSE protocol
	 * @return the secured paths
	 */
	@Bean
	@ConditionalOnMissingBean(name = "gatewayMcpSecuredPaths")
	SecuredPaths gatewayMcpSecuredPaths(ObjectProvider<McpServerStreamableHttpProperties> streamable,
			ObjectProvider<McpServerSseProperties> sse) {
		List<String> endpoints = new ArrayList<>();
		streamable.ifAvailable((properties) -> endpoints.add(properties.getMcpEndpoint()));
		sse.ifAvailable((properties) -> {
			endpoints.add(properties.getSseEndpoint());
			endpoints.add(properties.getSseMessageEndpoint());
		});
		return SecuredPaths.api(endpoints.toArray(String[]::new));
	}

	/**
	 * Serves the figures of the metrics plugin once it has put a route source in the
	 * context.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RouteMetricsSource.class)
	@ConditionalOnBean(RouteMetricsSource.class)
	static class MetricsToolsConfiguration {

		@Bean
		MetricsTools gatewayMcpMetricsTools(RouteMetricsSource routes,
				ObjectProvider<InstanceMetricsSource> instances) {
			return new MetricsTools(routes, instances);
		}

	}

	/**
	 * Serves the findings of the passive analyser once it is wired.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RouteScoreService.class)
	@ConditionalOnBean(RouteScoreService.class)
	static class PentestToolsConfiguration {

		@Bean
		PentestTools gatewayMcpPentestTools(FindingStore store, ReportService reports, RouteScoreService scores) {
			return new PentestTools(store, reports, scores);
		}

	}

	/**
	 * The tools that change the gateway, behind {@code write-enabled}. Each takes a
	 * {@code confirm} argument that {@code confirm-writes} makes mandatory.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.mcp.write-enabled", havingValue = "true")
	static class WriteToolsConfiguration {

		@Bean
		WriteGuard gatewayMcpWriteGuard(GatewayMcpProperties properties) {
			return new WriteGuard(properties.isConfirmWrites());
		}

		@Bean
		@ConditionalOnBean(RouteDefinitionWriter.class)
		RouteWriteTools gatewayMcpRouteWriteTools(RouteDefinitionWriter writer, ApplicationEventPublisher publisher,
				WriteGuard guard) {
			return new RouteWriteTools(writer, publisher, guard);
		}

		@Configuration(proxyBeanMethods = false)
		@ConditionalOnClass(FindingStore.class)
		@ConditionalOnBean(FindingStore.class)
		static class PentestWriteToolsConfiguration {

			@Bean
			PentestWriteTools gatewayMcpPentestWriteTools(FindingStore store, WriteGuard guard) {
				return new PentestWriteTools(store, guard);
			}

		}

	}

}
