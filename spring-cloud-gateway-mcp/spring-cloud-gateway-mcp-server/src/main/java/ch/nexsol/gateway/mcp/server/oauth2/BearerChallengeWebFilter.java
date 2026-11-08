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

package ch.nexsol.gateway.mcp.server.oauth2;

import java.util.List;
import java.util.Set;

import reactor.core.publisher.Mono;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

/**
 * Completes the {@code 401} of the MCP endpoints with the challenge an MCP client needs:
 * a {@code WWW-Authenticate: Bearer} naming the metadata of the protected resource, from
 * which the client finds the authorization server and opens its login.
 * <p>
 * The status is set by whichever security chain governs the endpoint, so the header is
 * added as the response commits rather than by the chain: the challenge is the same
 * whether the chain is the console's or one of the application's own.
 */
public final class BearerChallengeWebFilter implements WebFilter, Ordered {

	private final ProtectedResource resource;

	private final Set<String> endpoints;

	/**
	 * Creates the filter.
	 * @param resource the protected resource the challenge names
	 * @param endpoints the paths of the MCP endpoints
	 */
	public BearerChallengeWebFilter(ProtectedResource resource, List<String> endpoints) {
		this.resource = resource;
		this.endpoints = Set.copyOf(endpoints);
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		if (!this.endpoints.contains(exchange.getRequest().getPath().pathWithinApplication().value())) {
			return chain.filter(exchange);
		}
		ServerHttpResponse response = exchange.getResponse();
		response.beforeCommit(() -> Mono.fromRunnable(() -> {
			if (HttpStatus.UNAUTHORIZED.equals(response.getStatusCode())) {
				HttpHeaders headers = response.getHeaders();
				headers.set(HttpHeaders.WWW_AUTHENTICATE, this.resource
					.challenge(headers.getFirst(HttpHeaders.WWW_AUTHENTICATE), exchange.getRequest().getURI()));
			}
		}));
		return chain.filter(exchange);
	}

}
