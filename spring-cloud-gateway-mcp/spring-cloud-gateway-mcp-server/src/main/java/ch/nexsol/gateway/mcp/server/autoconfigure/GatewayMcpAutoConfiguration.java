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

import ch.nexsol.gateway.commons.security.SecuredPaths;
import ch.nexsol.gateway.mcp.server.metrics.MetricsTools;
import ch.nexsol.gateway.mcp.server.pentest.PentestTools;
import ch.nexsol.gateway.mcp.server.prompts.GatewayPrompts;
import ch.nexsol.gateway.mcp.server.routes.RouteTools;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import ch.nexsol.gateway.pentest.core.report.ReportService;
import ch.nexsol.gateway.pentest.core.store.FindingStore;
import ch.nexsol.gateway.pentest.passive.score.RouteScoreService;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
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
 * Ordered ahead of the Spring AI server auto-configurations on purpose: the scanner
 * registers a bean when that bean is initialised, and the server reads the registry once,
 * when it is built. A tool bean created after the server is never listed.
 */
@AutoConfiguration(
		before = { McpServerStatelessAutoConfiguration.class,
				StatelessServerSpecificationFactoryAutoConfiguration.class },
		afterName = { "ch.nexsol.gateway.metrics.autoconfigure.MetricsAutoConfiguration",
				"ch.nexsol.gateway.pentest.passive.autoconfigure.PassiveScanAutoConfiguration" })
@ConditionalOnClass(McpTool.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.mcp.enabled", havingValue = "true")
public class GatewayMcpAutoConfiguration {

	@Bean
	RouteTools gatewayMcpRouteTools(ObjectProvider<RouteDefinitionLocator> locators,
			ObjectProvider<GatewayFilterFactory<?>> filterFactories,
			ObjectProvider<RoutePredicateFactory<?>> predicateFactories) {
		return new RouteTools(locators, filterFactories, predicateFactories);
	}

	@Bean
	GatewayPrompts gatewayMcpPrompts() {
		return new GatewayPrompts();
	}

	@Bean
	@ConditionalOnMissingBean(name = "gatewayMcpSecuredPaths")
	SecuredPaths gatewayMcpSecuredPaths(
			@Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) {
		return SecuredPaths.api(endpoint);
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

}
