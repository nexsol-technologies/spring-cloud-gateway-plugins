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

import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * Carries the {@code Authorization} header of the MCP request into the transport context,
 * under the key the invoker reads it from, so a tool call is made as the caller.
 */
public final class AuthorizationContextExtractor implements McpTransportContextExtractor<ServerRequest> {

	@Override
	public McpTransportContext extract(ServerRequest request) {
		String authorization = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
		return (authorization != null) ? McpTransportContext.create(Map.of(ApiToolInvoker.AUTHORIZATION, authorization))
				: McpTransportContext.EMPTY;
	}

}
