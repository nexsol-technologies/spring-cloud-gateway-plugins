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

package ch.nexsol.gateway.mcp.hub;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.swagger.v3.oas.models.PathItem;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class ApiToolInvokerTests {

	private final AtomicReference<ClientRequest> sent = new AtomicReference<>();

	private final AtomicReference<String> sentBody = new AtomicReference<>();

	private final ApiOperation listPets = new ApiOperation("petstore", PathItem.HttpMethod.GET,
			"http://gateway/petstore/pets",
			Map.of("limit", ApiOperation.Location.QUERY, "X-Tenant", ApiOperation.Location.HEADER), null);

	private final ApiOperation getPet = new ApiOperation("petstore", PathItem.HttpMethod.GET,
			"http://gateway/petstore/pets/{petId}", Map.of("petId", ApiOperation.Location.PATH), null);

	private final ApiOperation createPet = new ApiOperation("petstore", PathItem.HttpMethod.POST,
			"http://gateway/petstore/pets", Map.of(), "body");

	@Test
	void writesTheArgumentsWhereTheContractDeclaresThemAndForwardsTheCallersAuthorization() {
		ApiToolInvoker invoker = invoker(HttpStatus.OK, "[{\"name\":\"Rex\"}]");
		McpTransportContext context = McpTransportContext.create(Map.of(ApiToolInvoker.AUTHORIZATION, "Bearer t"));
		StepVerifier.create(invoker.call(this.listPets, Map.of("limit", 5, "X-Tenant", "acme"), context))
			.assertNext((result) -> {
				assertThat(result.isError()).isFalse();
				assertThat(text(result)).isEqualTo("[{\"name\":\"Rex\"}]");
			})
			.verifyComplete();
		ClientRequest request = this.sent.get();
		assertThat(request.url()).hasToString("http://gateway/petstore/pets?limit=5");
		assertThat(request.headers().getFirst("X-Tenant")).isEqualTo("acme");
		assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer t");
	}

	@Test
	void encodesAPathParameterAndMakesTheCallAnonymouslyWhenTheCallerSentNoAuthorization() {
		ApiToolInvoker invoker = invoker(HttpStatus.OK, "{}");
		StepVerifier.create(invoker.call(this.getPet, Map.of("petId", "a b/c"), McpTransportContext.EMPTY))
			.assertNext((result) -> assertThat(result.isError()).isFalse())
			.verifyComplete();
		assertThat(this.sent.get().url()).hasToString("http://gateway/petstore/pets/a%20b%2Fc");
		assertThat(this.sent.get().headers().containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
	}

	@Test
	void sendsTheBodyArgumentAsJson() {
		ApiToolInvoker invoker = invoker(HttpStatus.CREATED, "");
		StepVerifier
			.create(invoker.call(this.createPet, Map.of("body", Map.of("name", "Rex")), McpTransportContext.EMPTY))
			.assertNext((result) -> assertThat(text(result)).isEqualTo("HTTP 201"))
			.verifyComplete();
		assertThat(this.sent.get().headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(this.sentBody.get()).isEqualTo("{\"name\":\"Rex\"}");
	}

	@Test
	void describesAChangeInsteadOfMakingItUntilTheCallIsConfirmed() {
		WebClient client = WebClient.builder().exchangeFunction((request) -> {
			this.sent.set(request);
			return Mono.just(ClientResponse.create(HttpStatus.CREATED).build());
		}).build();
		ApiToolInvoker invoker = new ApiToolInvoker(client, Duration.ofSeconds(5), true);
		StepVerifier
			.create(invoker.call(this.createPet, Map.of("body", Map.of("name", "Rex")), McpTransportContext.EMPTY))
			.assertNext((result) -> {
				assertThat(result.isError()).isFalse();
				assertThat(text(result)).contains("POST http://gateway/petstore/pets").contains("confirm=true");
			})
			.verifyComplete();
		assertThat(this.sent.get()).isNull();
		StepVerifier
			.create(invoker.call(this.createPet, Map.of("body", Map.of("name", "Rex"), "confirm", true),
					McpTransportContext.EMPTY))
			.assertNext((result) -> assertThat(text(result)).isEqualTo("HTTP 201"))
			.verifyComplete();
		assertThat(this.sent.get()).isNotNull();
		// A read is never held back.
		StepVerifier.create(invoker.call(this.listPets, Map.of(), McpTransportContext.EMPTY))
			.assertNext((result) -> assertThat(text(result)).isEqualTo("HTTP 201"))
			.verifyComplete();
	}

	@Test
	void reportsARefusalAsAnErrorResultCarryingTheStatusAndTheBody() {
		ApiToolInvoker invoker = invoker(HttpStatus.FORBIDDEN, "{\"error\":\"nope\"}");
		StepVerifier.create(invoker.call(this.listPets, Map.of(), McpTransportContext.EMPTY)).assertNext((result) -> {
			assertThat(result.isError()).isTrue();
			assertThat(text(result)).startsWith("HTTP 403").contains("nope");
		}).verifyComplete();
	}

	@Test
	void reportsACallThatCouldNotBeMadeAsAnErrorResult() {
		WebClient client = WebClient.builder()
			.exchangeFunction((request) -> Mono.error(new IllegalStateException("connection refused")))
			.build();
		ApiToolInvoker invoker = new ApiToolInvoker(client, Duration.ofSeconds(1), false);
		StepVerifier.create(invoker.call(this.listPets, Map.of(), McpTransportContext.EMPTY)).assertNext((result) -> {
			assertThat(result.isError()).isTrue();
			assertThat(text(result)).contains("connection refused");
		}).verifyComplete();
	}

	private ApiToolInvoker invoker(HttpStatus status, String body) {
		WebClient client = WebClient.builder().exchangeFunction((request) -> {
			this.sent.set(request);
			return capture(request).then(Mono.fromSupplier(() -> ClientResponse.create(status)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body(body)
				.build()));
		}).build();
		return new ApiToolInvoker(client, Duration.ofSeconds(5), false);
	}

	private Mono<Void> capture(ClientRequest request) {
		// The body is written to a recording request; what it wrote is what was sent.
		MockClientHttpRequest recorder = new MockClientHttpRequest(request.method(), request.url());
		return request.writeTo(recorder, ExchangeStrategies.withDefaults())
			.then(Mono.defer(recorder::getBodyAsString).onErrorReturn(""))
			.doOnNext(this.sentBody::set)
			.then();
	}

	private static String text(CallToolResult result) {
		List<Content> content = result.content();
		return ((TextContent) content.get(0)).text();
	}

}
