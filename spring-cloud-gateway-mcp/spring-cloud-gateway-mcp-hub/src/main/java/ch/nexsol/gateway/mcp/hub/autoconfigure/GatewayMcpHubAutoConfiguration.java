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

package ch.nexsol.gateway.mcp.hub.autoconfigure;

import java.net.URI;

import ch.nexsol.gateway.mcp.hub.ApiToolInvoker;
import ch.nexsol.gateway.mcp.hub.ApiToolSource;
import ch.nexsol.gateway.mcp.hub.ContractCatalog;
import ch.nexsol.gateway.mcp.hub.HubToolRegistrar;
import ch.nexsol.gateway.mcp.hub.McpHubProperties;
import ch.nexsol.gateway.openapi.HubOpenapiProperties;
import ch.nexsol.gateway.openapi.hub.SpringDocOpenapiRoutes;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;

import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Auto-configuration of the hub bridge: the catalog over the documentation routes of the
 * OpenAPI hub, the invoker that runs a tool call through the gateway, and the registrar
 * that keeps the tools of the server in step with the contracts, whichever server Spring
 * AI built, stateless or stateful. Active when both the bridge and the hub are on.
 */
@AutoConfiguration(after = { McpServerStatelessAutoConfiguration.class, McpServerAutoConfiguration.class },
		afterName = { "ch.nexsol.gateway.openapi.autoconfigure.HubApiAutoConfiguration",
				"ch.nexsol.gateway.mcp.server.autoconfigure.GatewayMcpAutoConfiguration" })
@ConditionalOnClass({ SpringDocOpenapiRoutes.class, McpStatelessAsyncServer.class })
@ConditionalOnProperty(name = { "spring.cloud.gateway.server.webflux.mcp.hub.enabled",
		"spring.cloud.gateway.server.webflux.hub-openapi.enabled" }, havingValue = "true")
public class GatewayMcpHubAutoConfiguration {

	@Bean
	@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.mcp.hub")
	McpHubProperties gatewayMcpHubProperties() {
		return new McpHubProperties();
	}

	/**
	 * The client the contracts are read and the tool calls made with. It resolves an
	 * {@code lb://} service through the load balancer when the application has one, and
	 * buffers a response up to {@code max-response-size}, the larger contracts included.
	 * @param properties the bridge settings
	 * @param hubProperties the hub settings, for the size a contract may reach
	 * @param loadBalancer the load balancer filter, when the application has one
	 * @return the client
	 */
	@Bean
	@ConditionalOnMissingBean(name = "gatewayMcpHubWebClient")
	WebClient gatewayMcpHubWebClient(McpHubProperties properties, HubOpenapiProperties hubProperties,
			ObjectProvider<ReactorLoadBalancerExchangeFilterFunction> loadBalancer) {
		long bytes = Math.max(properties.getMaxResponseSize().toBytes(), hubProperties.getMaxDocumentSize().toBytes());
		int limit = (int) Math.min(bytes, Integer.MAX_VALUE);
		WebClient.Builder builder = WebClient.builder()
			.exchangeStrategies(ExchangeStrategies.builder()
				.codecs((codecs) -> codecs.defaultCodecs().maxInMemorySize(limit))
				.build());
		loadBalancer.ifAvailable(builder::filter);
		return builder.build();
	}

	@Bean
	@ConditionalOnMissingBean(ApiToolSource.class)
	ContractCatalog gatewayMcpHubContractCatalog(ObjectProvider<RouteDefinitionLocator> locators,
			WebClient gatewayMcpHubWebClient,
			@Value("${spring.cloud.gateway.server.webflux.hub-openapi.gateway-uri}") URI gatewayUri,
			McpHubProperties properties) {
		return new ContractCatalog(locators, gatewayMcpHubWebClient, gatewayUri, properties);
	}

	@Bean
	ApiToolInvoker gatewayMcpHubInvoker(WebClient gatewayMcpHubWebClient, McpHubProperties properties) {
		return new ApiToolInvoker(gatewayMcpHubWebClient, properties.getTimeout(), properties.isConfirmWrites());
	}

	/**
	 * The registrar over the stateless server, the one the MCP plugin runs by default.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBean(McpStatelessAsyncServer.class)
	static class StatelessRegistrarConfiguration {

		@Bean
		HubToolRegistrar gatewayMcpHubToolRegistrar(ApiToolSource source, ApiToolInvoker invoker,
				McpStatelessAsyncServer server) {
			return new HubToolRegistrar(source, invoker, HubToolRegistrar.ToolRegistry.of(server));
		}

	}

	/**
	 * The registrar over the stateful server of the streamable and SSE protocols.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBean(McpAsyncServer.class)
	static class StatefulRegistrarConfiguration {

		@Bean
		HubToolRegistrar gatewayMcpHubToolRegistrar(ApiToolSource source, ApiToolInvoker invoker,
				McpAsyncServer server) {
			return new HubToolRegistrar(source, invoker, HubToolRegistrar.ToolRegistry.of(server));
		}

	}

}
