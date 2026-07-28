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

package ch.nexsol.gateway.audit.webfilter;

import ch.nexsol.gateway.audit.AuditEventFactory;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

/**
 * Global {@link WebFilter} auditing every request and response. Registered only when
 * {@code spring.cloud.gateway.server.webflux.audit.web-filter.enabled} is {@code true}.
 * <p>
 * Runs at {@link Ordered#LOWEST_PRECEDENCE} so it sees the authenticated principal wired
 * by the security web filters and captures the fully written response.
 */
public class AuditWebFilter implements WebFilter, Ordered {

	private static final Logger LOG = LoggerFactory.getLogger(AuditWebFilter.class);

	private final AuditEventFactory eventFactory;

	private final AuditEventPublisher publisher;

	/**
	 * Create a new filter.
	 * @param eventFactory the factory building the audit event
	 * @param publisher the publisher delivering the audit event
	 */
	public AuditWebFilter(AuditEventFactory eventFactory, AuditEventPublisher publisher) {
		this.eventFactory = eventFactory;
		this.publisher = publisher;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		// The exchange is audited whatever its outcome: an upstream that refused the
		// connection or timed out is exactly what an audit trail has to keep, so the
		// event is published before the failure is propagated.
		return chain.filter(exchange).then(audit(exchange)).onErrorResume((ex) -> audit(exchange).then(Mono.error(ex)));
	}

	private Mono<Void> audit(ServerWebExchange exchange) {
		return Mono.defer(() -> this.eventFactory.create(exchange))
			.doOnNext(this.publisher::publish)
			.onErrorResume((ex) -> {
				LOG.warn("audit event publication failed", ex);
				return Mono.empty();
			})
			.then();
	}

}
