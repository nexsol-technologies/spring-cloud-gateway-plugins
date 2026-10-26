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

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Executes a tool call as the HTTP request the operation describes, through the gateway:
 * the call enters the gateway like any other, so its routes, filters, audit and metrics
 * see it. The {@code Authorization} header the MCP client sent is forwarded, so the
 * downstream request is made as the caller, not as the gateway.
 */
public class ApiToolInvoker {

	/**
	 * The key the transport context carries the caller's {@code Authorization} under.
	 */
	public static final String AUTHORIZATION = "authorization";

	private final WebClient webClient;

	private final Duration timeout;

	private final boolean confirmWrites;

	private final JsonMapper json = JsonMapper.builder().build();

	public ApiToolInvoker(WebClient webClient, Duration timeout, boolean confirmWrites) {
		this.webClient = webClient;
		this.timeout = timeout;
		this.confirmWrites = confirmWrites;
	}

	/**
	 * Call an operation with the arguments of a tool call.
	 * @param operation the operation
	 * @param arguments the arguments, by name
	 * @param context the transport context of the call, carrying the caller's
	 * {@code Authorization} if any
	 * @return the result: the body of the response, an error result for a status of 400
	 * or more and for a call that could not be made
	 */
	public Mono<CallToolResult> call(ApiOperation operation, Map<String, Object> arguments,
			McpTransportContext context) {
		Map<String, Object> given = (arguments != null) ? arguments : Map.of();
		if (this.confirmWrites && !OperationTools.isReadOnly(operation.method())
				&& !Boolean.TRUE.equals(given.get(OperationTools.CONFIRM))) {
			return Mono.just(CallToolResult.builder()
				.addTextContent("This call changes state: " + operation.method() + " " + operation.urlTemplate()
						+ " of " + operation.service()
						+ ". Nothing was sent. Call it again with confirm=true to proceed.")
				.isError(false)
				.build());
		}
		return Mono.defer(() -> request(operation, given, context))
			.timeout(this.timeout)
			.onErrorResume((ex) -> Mono.just(error(describe(ex))));
	}

	private Mono<CallToolResult> request(ApiOperation operation, Map<String, Object> arguments,
			McpTransportContext context) {
		WebClient.RequestBodySpec request = this.webClient.method(HttpMethod.valueOf(operation.method().name()))
			.uri(uri(operation, arguments))
			.accept(MediaType.APPLICATION_JSON, MediaType.ALL);
		for (String header : operation.parametersAt(ApiOperation.Location.HEADER)) {
			if (arguments.get(header) != null) {
				request.header(header, text(arguments.get(header)));
			}
		}
		String cookies = operation.parametersAt(ApiOperation.Location.COOKIE)
			.stream()
			.filter((cookie) -> arguments.get(cookie) != null)
			.map((cookie) -> cookie + "=" + text(arguments.get(cookie)))
			.collect(Collectors.joining("; "));
		if (StringUtils.hasText(cookies)) {
			request.header(HttpHeaders.COOKIE, cookies);
		}
		Object authorization = (context != null) ? context.get(AUTHORIZATION) : null;
		if (authorization != null) {
			request.header(HttpHeaders.AUTHORIZATION, authorization.toString());
		}
		WebClient.RequestHeadersSpec<?> ready = request;
		if (operation.bodyArgument() != null && arguments.get(operation.bodyArgument()) != null) {
			ready = request.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(this.json.writeValueAsString(arguments.get(operation.bodyArgument())));
		}
		return ready.exchangeToMono((response) -> response.bodyToMono(String.class)
			.defaultIfEmpty("")
			.map((body) -> result(response.statusCode(), body)));
	}

	private static URI uri(ApiOperation operation, Map<String, Object> arguments) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(operation.urlTemplate());
		Map<String, Object> variables = new LinkedHashMap<>();
		for (String path : operation.parametersAt(ApiOperation.Location.PATH)) {
			variables.put(path, text(arguments.get(path)));
		}
		for (String query : operation.parametersAt(ApiOperation.Location.QUERY)) {
			Object value = arguments.get(query);
			if (value == null) {
				continue;
			}
			// Query values go through the template too, so they are encoded with the
			// path variables, by the same rules.
			if (value instanceof Collection<?> values) {
				int i = 0;
				for (Object element : values) {
					String variable = query + "__" + (i++);
					builder.queryParam(query, "{" + variable + "}");
					variables.put(variable, text(element));
				}
			}
			else {
				builder.queryParam(query, "{" + query + "}");
				variables.put(query, text(value));
			}
		}
		return builder.encode().build().expand(variables).toUri();
	}

	private static String text(Object value) {
		return (value != null) ? String.valueOf(value) : "";
	}

	private static CallToolResult result(HttpStatusCode status, String body) {
		if (status.isError()) {
			return error("HTTP " + status.value() + (body.isEmpty() ? "" : "\n" + body));
		}
		return CallToolResult.builder()
			.addTextContent(body.isEmpty() ? "HTTP " + status.value() : body)
			.isError(false)
			.build();
	}

	private static CallToolResult error(String message) {
		return CallToolResult.builder().addTextContent(message).isError(true).build();
	}

	private static String describe(Throwable ex) {
		if (ex instanceof DataBufferLimitException) {
			return "The response is larger than spring.cloud.gateway.server.webflux.mcp.hub.max-response-size";
		}
		return (ex.getMessage() != null) ? ex.getClass().getSimpleName() + ": " + ex.getMessage()
				: ex.getClass().getSimpleName();
	}

}
