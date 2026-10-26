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

import ch.nexsol.gateway.mcp.hub.AuthorizationContextExtractor;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerSseProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerSseWebFluxAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStatelessWebFluxAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxSseServerTransportProvider;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Hands the transport of the protocol in force an extractor that carries the caller's
 * {@code Authorization} header into the transport context, where the invoker reads it to
 * make the downstream call as the caller. Spring AI builds its transports without one and
 * backs off when a transport bean is already there, hence this configuration ahead of its
 * own, with the same settings plus the extractor.
 */
@AutoConfiguration(before = { McpServerStatelessWebFluxAutoConfiguration.class,
		McpServerStreamableHttpWebFluxAutoConfiguration.class, McpServerSseWebFluxAutoConfiguration.class })
@ConditionalOnClass({ McpSchema.class, WebFluxStatelessServerTransport.class })
@ConditionalOnProperty(name = { "spring.cloud.gateway.server.webflux.mcp.hub.enabled",
		"spring.cloud.gateway.server.webflux.hub-openapi.enabled" }, havingValue = "true")
@EnableConfigurationProperties({ McpServerStreamableHttpProperties.class, McpServerSseProperties.class })
public class GatewayMcpHubTransportAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(name = "spring.ai.mcp.server.protocol", havingValue = "STATELESS")
	WebFluxStatelessServerTransport webFluxStatelessServerTransport(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
			McpServerStreamableHttpProperties serverProperties) {
		return WebFluxStatelessServerTransport.builder()
			.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
			.messageEndpoint(serverProperties.getMcpEndpoint())
			.contextExtractor(new AuthorizationContextExtractor())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(name = "spring.ai.mcp.server.protocol", havingValue = "STREAMABLE", matchIfMissing = true)
	WebFluxStreamableServerTransportProvider webFluxStreamableServerTransportProvider(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
			McpServerStreamableHttpProperties serverProperties) {
		return WebFluxStreamableServerTransportProvider.builder()
			.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
			.messageEndpoint(serverProperties.getMcpEndpoint())
			.keepAliveInterval(serverProperties.getKeepAliveInterval())
			.disallowDelete(serverProperties.isDisallowDelete())
			.contextExtractor(new AuthorizationContextExtractor())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(name = "spring.ai.mcp.server.protocol", havingValue = "SSE")
	WebFluxSseServerTransportProvider webFluxSseServerTransportProvider(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerSseProperties serverProperties) {
		return WebFluxSseServerTransportProvider.builder()
			.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
			.basePath(serverProperties.getBaseUrl())
			.messageEndpoint(serverProperties.getSseMessageEndpoint())
			.sseEndpoint(serverProperties.getSseEndpoint())
			.keepAliveInterval(serverProperties.getKeepAliveInterval())
			.contextExtractor(new AuthorizationContextExtractor())
			.build();
	}

}
