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

package ch.nexsol.gateway.filter;

import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import reactor.core.publisher.Mono;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

/**
 * @author guerricmerle
 */
public class CorrelationIdFilter implements WebFilter, Ordered {

	private static final String X_CORRELATION_ID = "x-correlation-id";

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 3;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		return chain.filter(exchange).then(addCustomTraceHeader(exchange));
	}

	private Mono<Void> addCustomTraceHeader(ServerWebExchange exchange) {
		ServerHttpResponse response = exchange.getResponse();
		final Object context = exchange
			.getAttribute("org.springframework.http.server.reactive.observation.ServerRequestObservationContext");
		final AtomicReference<String> traceId = new AtomicReference<>();
		if (context instanceof ServerRequestObservationContext realcontext) {
			TracingContext tracingContext = realcontext.get(TracingContext.class);
			if (tracingContext != null) {
				Span realSpan = tracingContext.getSpan();
				if (realSpan != null && realSpan.context() != null) {
					traceId.set(realSpan.context().traceId());
				}
			}
		}
		if (StringUtils.hasText(traceId.get())) {
			if (response.isCommitted()) {
				response.getHeaders().add(X_CORRELATION_ID, traceId.get());
			}
			else {
				response.beforeCommit(() -> {
					response.getHeaders().add(X_CORRELATION_ID, traceId.get());
					return Mono.empty();
				});
			}
		}
		return Mono.empty();
	}

}
