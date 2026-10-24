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

import java.net.URI;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

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
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.hamcrest.Matchers.containsString;

/**
 * Drives the server Spring AI builds from the beans of the plugin, over HTTP, with the
 * JSON-RPC messages an MCP client sends.
 */
class GatewayMcpServerIntegrationTests {

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":1,"method":"tools/list"}""";

	private static final String CALL_LIST_ROUTES = """
			{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_routes","arguments":{}}}""";

	private static final String CALL_GET_UNKNOWN_ROUTE = """
			{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_route","arguments":{"routeId":"nowhere"}}}""";

	private static final String GET_DIAGNOSE_ROUTE = """
			{"jsonrpc":"2.0","id":4,"method":"prompts/get","params":{"name":"diagnose-route","arguments":{"routeId":"route-x"}}}""";

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, McpServerStatelessAutoConfiguration.class,
				McpServerStatelessWebFluxAutoConfiguration.class, McpServerAnnotationScannerAutoConfiguration.class,
				StatelessServerSpecificationFactoryAutoConfiguration.class, GatewayMcpAutoConfiguration.class))
		.withUserConfiguration(OneRouteConfiguration.class)
		// A running application derives the Spring AI settings from the plugin switch
		// through the environment post-processor; a context runner does not run it.
		.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true",
				"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC");

	@Test
	void listsTheRouteToolsAndNoneOfTheAbsentPlugins() {
		this.runner.run((context) -> call(context, TOOLS_LIST).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.result.tools[?(@.name=='list_routes')]")
			.exists()
			.jsonPath("$.result.tools[?(@.name=='list_filter_factories')]")
			.exists()
			.jsonPath("$.result.tools[?(@.name=='route_traffic')]")
			.doesNotExist()
			.jsonPath("$.result.tools[?(@.name=='list_findings')]")
			.doesNotExist());
	}

	@Test
	void answersAToolCallWithTheRoutesAsJsonText() {
		this.runner.run((context) -> call(context, CALL_LIST_ROUTES).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.result.isError")
			.isEqualTo(false)
			.jsonPath("$.result.content[0].text")
			.value(containsString("\"routeId\":\"route-x\""))
			.jsonPath("$.result.content[0].text")
			.value(containsString("\"unreadableSources\":[]")));
	}

	@Test
	void conveysAFailedToolCallToTheClientAsAnErrorResult() {
		this.runner.run((context) -> call(context, CALL_GET_UNKNOWN_ROUTE).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.result.isError")
			.isEqualTo(true)
			.jsonPath("$.result.content[0].text")
			.value(containsString("nowhere")));
	}

	@Test
	void servesThePromptsWithTheirArgumentsFilledIn() {
		this.runner.run((context) -> call(context, GET_DIAGNOSE_ROUTE).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.result.messages[0].role")
			.isEqualTo("user")
			.jsonPath("$.result.messages[0].content.text")
			.value(containsString("get_route with routeId 'route-x'")));
	}

	private static WebTestClient.ResponseSpec call(AssertableReactiveWebApplicationContext context, String body) {
		return WebTestClient.bindToApplicationContext(context)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.bodyValue(body)
			.exchange();
	}

	@Configuration(proxyBeanMethods = false)
	static class OneRouteConfiguration {

		@Bean
		RouteDefinitionLocator oneRoute() {
			RouteDefinition definition = new RouteDefinition();
			definition.setId("route-x");
			definition.setUri(URI.create("http://x"));
			return () -> Flux.just(definition);
		}

	}

}
