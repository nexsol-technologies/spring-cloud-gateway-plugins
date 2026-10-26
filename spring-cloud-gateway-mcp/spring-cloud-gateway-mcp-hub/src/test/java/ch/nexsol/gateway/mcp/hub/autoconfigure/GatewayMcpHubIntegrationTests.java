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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import ch.nexsol.gateway.mcp.hub.HubToolRegistrar;
import ch.nexsol.gateway.mcp.server.autoconfigure.GatewayMcpAutoConfiguration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Drives the bridge end to end: a documentation route of the hub, its contract served by
 * a stub, the tools it yields on the server, and a call of one of them made through the
 * stub as the MCP caller.
 */
class GatewayMcpHubIntegrationTests {

	private static final String PETSTORE = """
			{"openapi":"3.0.3","info":{"title":"Petstore","version":"1"},"paths":{"/pets":{"get":{
			"operationId":"listPets","summary":"List the pets","parameters":[{"name":"limit","in":"query",
			"schema":{"type":"integer"}}],"responses":{"200":{"description":"ok"}}}}}}""";

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":1,"method":"tools/list"}""";

	private static final String CALL_LIST_PETS = """
			{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"petstore__list_pets","arguments":{"limit":3}}}""";

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, McpServerStatelessAutoConfiguration.class,
				McpServerStatelessWebFluxAutoConfiguration.class, McpServerAnnotationScannerAutoConfiguration.class,
				StatelessServerSpecificationFactoryAutoConfiguration.class, GatewayMcpAutoConfiguration.class,
				GatewayMcpHubTransportAutoConfiguration.class, GatewayMcpHubAutoConfiguration.class))
		.withUserConfiguration(HubScaffolding.class)
		.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true",
				"spring.cloud.gateway.server.webflux.mcp.hub.enabled=true",
				"spring.cloud.gateway.server.webflux.hub-openapi.enabled=true",
				"spring.cloud.gateway.server.webflux.hub-openapi.gateway-uri=http://gateway:8181",
				"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC");

	@Test
	void servesTheOperationsOfADocumentedServiceAsToolsAndCallsThemAsTheCaller() {
		this.runner.run((context) -> {
			context.getBean(HubToolRegistrar.class).sync().block();
			call(context, TOOLS_LIST, null).expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.result.tools[?(@.name=='petstore__list_pets')]")
				.exists()
				.jsonPath("$.result.tools[?(@.name=='list_routes')]")
				.exists();
			call(context, CALL_LIST_PETS, "Bearer agent-token").expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.result.isError")
				.isEqualTo(false)
				.jsonPath("$.result.content[0].text")
				.value(containsString("Rex"));
			ClientRequest downstream = context.getBean(HubScaffolding.class).apiCall.get();
			assertThat(downstream.url()).hasToString("http://gateway:8181/petstore/pets?limit=3");
			assertThat(downstream.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer agent-token");
		});
	}

	private static WebTestClient.ResponseSpec call(AssertableReactiveWebApplicationContext context, String body,
			String authorization) {
		WebTestClient.RequestBodySpec request = WebTestClient.bindToApplicationContext(context)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM);
		if (authorization != null) {
			request.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return request.bodyValue(body).exchange();
	}

	@Configuration(proxyBeanMethods = false)
	static class HubScaffolding {

		final AtomicReference<ClientRequest> apiCall = new AtomicReference<>();

		@Bean
		RouteDefinitionLocator petstoreDocumentation() {
			RouteDefinition route = new RouteDefinition();
			route.setId("openapi-docs-discovery-petstore");
			route.setUri(URI.create("http://svc:8080"));
			route.setMetadata(Map.of("name", "petstore"));
			route.setPredicates(List.of(new PredicateDefinition("Path=/v3/api-docs/petstore")));
			route.setFilters(List.of(new FilterDefinition("RewritePath=/v3/api-docs/petstore, /v3/api-docs"),
					new FilterDefinition("OpenapiModifyResponseBody=/petstore")));
			return () -> Flux.just(route);
		}

		@Bean
		WebClient gatewayMcpHubWebClient() {
			return WebClient.builder().exchangeFunction((request) -> {
				String body = PETSTORE;
				if ("gateway".equals(request.url().getHost())) {
					this.apiCall.set(request);
					body = "[{\"name\":\"Rex\"}]";
				}
				return Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.body(body)
					.build());
			}).build();
		}

	}

}
