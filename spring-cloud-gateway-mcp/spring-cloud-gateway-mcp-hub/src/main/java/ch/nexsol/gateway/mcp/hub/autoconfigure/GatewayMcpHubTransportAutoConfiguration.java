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

import java.util.Map;

import ch.nexsol.gateway.mcp.hub.ApiToolInvoker;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStatelessWebFluxAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

/**
 * Hands the stateless transport an extractor that carries the caller's
 * {@code Authorization} header into the transport context, where the invoker reads it to
 * make the downstream call as the caller. Spring AI builds its transport without one and
 * backs off when a transport bean is already there, hence this configuration ahead of its
 * own.
 */
@AutoConfiguration(before = McpServerStatelessWebFluxAutoConfiguration.class)
@ConditionalOnClass({ McpSchema.class, WebFluxStatelessServerTransport.class })
@ConditionalOnProperty(name = { "spring.cloud.gateway.server.webflux.mcp.hub.enabled",
		"spring.cloud.gateway.server.webflux.hub-openapi.enabled" }, havingValue = "true")
@EnableConfigurationProperties(McpServerStreamableHttpProperties.class)
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
			.contextExtractor((request) -> {
				String authorization = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
				return (authorization != null)
						? McpTransportContext.create(Map.of(ApiToolInvoker.AUTHORIZATION, authorization))
						: McpTransportContext.EMPTY;
			})
			.build();
	}

}
