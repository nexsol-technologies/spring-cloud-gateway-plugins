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

package ch.nexsol.gateway.passivescan.webfilter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ch.nexsol.gateway.passivescan.engine.PassiveScanEngine;
import ch.nexsol.gateway.passivescan.model.HttpExchange;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Global {@link WebFilter} snapshotting every routed exchange once its response has been
 * written, and submitting the snapshot to the {@link PassiveScanEngine} off the hot path.
 * <p>
 * Ordered {@link Ordered#LOWEST_PRECEDENCE} so it observes the response the client
 * actually receives. When body capture is enabled the response is decorated to copy a
 * bounded prefix of its body; otherwise only request and response metadata are read.
 */
public class PassiveScanWebFilter implements WebFilter, Ordered {

	private final PassiveScanEngine engine;

	private final List<PathPattern> excludedPaths;

	private final boolean captureBody;

	private final int maxBodyBytes;

	public PassiveScanWebFilter(PassiveScanEngine engine, List<String> excludePaths, boolean captureBody,
			int maxBodyBytes) {
		this.engine = engine;
		this.excludedPaths = excludePaths.stream().map(PathPatternParser.defaultInstance::parse).toList();
		this.captureBody = captureBody;
		this.maxBodyBytes = maxBodyBytes;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		if (isExcluded(exchange)) {
			return chain.filter(exchange);
		}
		if (!this.captureBody) {
			return submitAfter(chain.filter(exchange), exchange, () -> null);
		}
		CapturingServerHttpResponse response = new CapturingServerHttpResponse(exchange.getResponse(),
				this.maxBodyBytes);
		ServerWebExchange mutated = exchange.mutate().response(response).build();
		return submitAfter(chain.filter(mutated), mutated, response::capturedBody);
	}

	private Mono<Void> submitAfter(Mono<Void> action, ServerWebExchange exchange,
			java.util.function.Supplier<String> body) {
		return action.then(Mono.<Void>fromRunnable(() -> this.engine.submit(snapshot(exchange, body.get()))))
			.onErrorResume((ex) -> {
				this.engine.submit(snapshot(exchange, body.get()));
				return Mono.<Void>error(ex);
			});
	}

	private boolean isExcluded(ServerWebExchange exchange) {
		PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
		return this.excludedPaths.stream().anyMatch((pattern) -> pattern.matches(path));
	}

	private HttpExchange snapshot(ServerWebExchange exchange, String body) {
		ServerHttpRequest request = exchange.getRequest();
		Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
		String routeId = (route != null) ? route.getId() : null;
		Map<String, String> routeMetadata = Map.of();
		if (route != null && route.getMetadata() != null) {
			routeMetadata = route.getMetadata()
				.entrySet()
				.stream()
				.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, (entry) -> String.valueOf(entry.getValue())));
		}
		String remoteIp = (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null)
				? request.getRemoteAddress().getAddress().getHostAddress() : null;
		return new HttpExchange(Instant.now(), request.getMethod().name(), request.getURI().getScheme(),
				request.getPath().value(), request.getQueryParams(),
				HttpHeaders.readOnlyHttpHeaders(request.getHeaders()),
				HttpHeaders.readOnlyHttpHeaders(exchange.getResponse().getHeaders()),
				exchange.getResponse().getStatusCode(), routeId, remoteIp, routeMetadata, body);
	}

}
