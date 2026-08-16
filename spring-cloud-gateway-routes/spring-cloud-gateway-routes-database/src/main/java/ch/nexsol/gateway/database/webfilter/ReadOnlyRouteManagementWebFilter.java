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

package ch.nexsol.gateway.database.webfilter;

import java.util.List;
import java.util.Set;

import ch.nexsol.gateway.database.RouteManagementPaths;
import reactor.core.publisher.Mono;

import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Answers {@code 405 Method Not Allowed} to anything that would change the routing table,
 * so a gateway can publish its route management for reading without publishing it for
 * writing.
 * <p>
 * This is not a security decision and does not replace one: it removes the operation
 * rather than asking who is calling it, which is exactly what a gateway with no way to
 * authenticate needs. A gateway that has one closes the same paths with the chain the
 * plugin contributes, and can do both.
 * <p>
 * The handlers themselves are left registered. Spring answers a path it serves under
 * another method with the same {@code 405}, so a client cannot tell the difference and
 * the plugin needs no second controller to maintain.
 */
public class ReadOnlyRouteManagementWebFilter implements WebFilter, Ordered {

	/**
	 * Runs before the request is routed, and before the security chain has anything to
	 * say: a method that is not served is not a question of who is asking.
	 */
	public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 50;

	private static final Set<HttpMethod> WRITING = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
			HttpMethod.DELETE);

	private final List<PathPattern> guarded;

	/**
	 * Creates the filter over the paths of the route management.
	 */
	public ReadOnlyRouteManagementWebFilter() {
		PathPatternParser parser = PathPatternParser.defaultInstance;
		this.guarded = RouteManagementPaths.API.stream().map(parser::parse).toList();
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		if (!WRITING.contains(exchange.getRequest().getMethod()) || !guards(exchange)) {
			return chain.filter(exchange);
		}
		exchange.getResponse().setStatusCode(HttpStatus.METHOD_NOT_ALLOWED);
		return exchange.getResponse().setComplete();
	}

	private boolean guards(ServerWebExchange exchange) {
		return this.guarded.stream()
			.anyMatch((pattern) -> pattern.matches(exchange.getRequest().getPath().pathWithinApplication()));
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

}
