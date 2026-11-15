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

package ch.nexsol.gateway.sample.mcp;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import ch.nexsol.gateway.mcp.hub.HubToolRegistrar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a service receives of the credentials presented to {@code /mcp}, and what its
 * contract may ask of a caller: the sample with a documented service whose contract and
 * calls a stub answers, standing for service-a and its {@code /whoami}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class McpGatewayCredentialsRelayTests {

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":1,"method":"tools/list"}""";

	private static final String CALL_WHOAMI = """
			{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo__whoami","arguments":{}}}""";

	// As springdoc writes it for a handler reading the header: a parameter of the
	// operation.
	private static final String CONTRACT = """
			{"openapi":"3.0.3","info":{"title":"Echo","version":"1"},"paths":{"/whoami":{"get":{
			"operationId":"whoami","parameters":[{"name":"Authorization","in":"header","required":false,
			"schema":{"type":"string"}}],"responses":{"200":{"description":"ok"}}}}}}""";

	@Autowired
	WebTestClient webTestClient;

	@Autowired
	HubToolRegistrar registrar;

	@Autowired
	EchoService echo;

	@BeforeEach
	void readTheContracts() {
		this.registrar.sync().block();
	}

	@Test
	void keepsTheBasicCredentialsOfTheEndpointOutOfTheCallToAService() {
		this.webTestClient.post()
			.uri("/mcp")
			.headers((headers) -> headers.setBasicAuth("admin", "admin"))
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.bodyValue(CALL_WHOAMI)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.result.isError")
			.isEqualTo(false);
		ClientRequest received = this.echo.call.get();
		assertThat(received.url().getPath()).isEqualTo("/echo/whoami");
		assertThat(received.headers().containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
	}

	@Test
	void takesNoAuthorizationArgumentFromAContractDeclaringTheHeader() {
		this.webTestClient.post()
			.uri("/mcp")
			.headers((headers) -> headers.setBasicAuth("admin", "admin"))
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.bodyValue(TOOLS_LIST)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.result.tools[?(@.name=='echo__whoami')]")
			.exists()
			.jsonPath("$.result.tools[?(@.name=='echo__whoami')].inputSchema.properties.Authorization")
			.doesNotExist();
	}

	/**
	 * A service the hub documents, and the client the bridge reads its contract and calls
	 * its operations with.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class EchoConfiguration {

		@Bean
		EchoService echoService() {
			return new EchoService();
		}

		@Bean
		RouteDefinitionLocator echoDocumentation() {
			RouteDefinition route = new RouteDefinition();
			route.setId("openapi-docs-discovery-echo");
			route.setUri(URI.create("http://echo:8080"));
			route.setMetadata(Map.of("name", "echo"));
			route.setPredicates(List.of(new PredicateDefinition("Path=/v3/api-docs/echo")));
			route.setFilters(List.of(new FilterDefinition("RewritePath=/v3/api-docs/echo, /v3/api-docs"),
					new FilterDefinition("OpenapiModifyResponseBody=/echo")));
			return () -> Flux.just(route);
		}

		@Bean
		WebClient gatewayMcpHubWebClient(EchoService echo) {
			return WebClient.builder().exchangeFunction((request) -> {
				if ("echo".equals(request.url().getHost())) {
					return answer(CONTRACT);
				}
				if ("/echo/whoami".equals(request.url().getPath())) {
					echo.call.set(request);
					return answer("{}");
				}
				return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
			}).build();
		}

		private static Mono<ClientResponse> answer(String body) {
			return Mono.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body(body)
				.build());
		}

	}

	static class EchoService {

		final AtomicReference<ClientRequest> call = new AtomicReference<>();

	}

}
