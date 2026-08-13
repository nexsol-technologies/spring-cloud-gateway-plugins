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

import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

/**
 * Web filter that copies the current trace identifier onto the response as an
 * {@code x-correlation-id} header so downstream clients can correlate the request.
 *
 * @author guerricmerle
 */
public class CorrelationIdFilter implements WebFilter, Ordered {

	private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);

	private static final String X_CORRELATION_ID = "x-correlation-id";

	/**
	 * {@inheritDoc}
	 * <p>
	 * Runs near the start of the chain so the trace context is available when the
	 * response is committed.
	 */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 3;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Adds the correlation header after the downstream chain has completed.
	 */
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		return chain.filter(exchange).then(addCustomTraceHeader(exchange));
	}

	private Mono<Void> addCustomTraceHeader(ServerWebExchange exchange) {
		ServerHttpResponse response = exchange.getResponse();
		final String traceId = traceId(exchange);
		if (!StringUtils.hasText(traceId)) {
			return Mono.empty();
		}
		if (response.isCommitted()) {
			// The headers are already on the wire: adding one now changes nothing but the
			// local copy, so say so rather than pretend the client got it.
			LOG.debug("Response already committed, not adding the {} header", X_CORRELATION_ID);
			return Mono.empty();
		}
		response.beforeCommit(() -> {
			response.getHeaders().add(X_CORRELATION_ID, traceId);
			return Mono.empty();
		});
		return Mono.empty();
	}

	private static String traceId(ServerWebExchange exchange) {
		final Object context = exchange
			.getAttribute("org.springframework.http.server.reactive.observation.ServerRequestObservationContext");
		if (context instanceof ServerRequestObservationContext realcontext) {
			TracingContext tracingContext = realcontext.get(TracingContext.class);
			if (tracingContext != null) {
				Span realSpan = tracingContext.getSpan();
				if (realSpan != null && realSpan.context() != null) {
					return realSpan.context().traceId();
				}
			}
		}
		return null;
	}

}
