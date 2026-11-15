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

import ch.nexsol.gateway.mcp.hub.HubToolRegistrar;
import ch.nexsol.gateway.mcp.server.autoconfigure.GatewayMcpAutoConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStatelessWebFluxAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableReactiveWebApplicationContext;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which credentials of the call to {@code /mcp} reach the service a tool calls: the
 * bridge end to end, over the scaffolding of {@link GatewayMcpHubIntegrationTests}.
 */
class GatewayMcpHubCredentialsTests {

	private static final String CALL_LIST_PETS = """
			{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"petstore__list_pets","arguments":{"limit":3}}}""";

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, McpServerStatelessAutoConfiguration.class,
				McpServerStatelessWebFluxAutoConfiguration.class, McpServerAnnotationScannerAutoConfiguration.class,
				StatelessServerSpecificationFactoryAutoConfiguration.class, GatewayMcpAutoConfiguration.class,
				GatewayMcpHubTransportAutoConfiguration.class, GatewayMcpHubAutoConfiguration.class))
		.withUserConfiguration(GatewayMcpHubIntegrationTests.HubScaffolding.class)
		.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true",
				"spring.cloud.gateway.server.webflux.mcp.hub.enabled=true",
				"spring.cloud.gateway.server.webflux.hub-openapi.enabled=true",
				"spring.cloud.gateway.server.webflux.hub-openapi.gateway-uri=http://gateway:8181",
				"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC");

	@Test
	void relaysTheBearerTokenOfTheCaller() {
		this.runner.run((context) -> {
			ClientRequest downstream = callListPets(context, "Bearer agent-token");
			assertThat(downstream.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer agent-token");
		});
	}

	@Test
	void keepsTheBasicCredentialsOfTheEndpointOutOfTheCallItMakes() {
		// They are those of /mcp itself, its local user or the directory of the gateway:
		// relayed, that password would reach every service a tool calls.
		this.runner.run((context) -> {
			ClientRequest downstream = callListPets(context, "Basic YWRtaW46bWNwLXNlY3JldA==");
			assertThat(downstream.headers().containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
		});
	}

	private static ClientRequest callListPets(AssertableReactiveWebApplicationContext context, String authorization) {
		context.getBean(HubToolRegistrar.class).sync().block();
		WebTestClient.bindToApplicationContext(context)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header(HttpHeaders.AUTHORIZATION, authorization)
			.bodyValue(CALL_LIST_PETS)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.result.isError")
			.isEqualTo(false);
		return context.getBean(GatewayMcpHubIntegrationTests.HubScaffolding.class).apiCall.get();
	}

}
