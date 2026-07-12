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

package ch.nexsol.gateway.audit.factory;

import ch.nexsol.gateway.audit.AuditEventFactory;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;

/**
 * Gateway filter factory that audits the request and response of the route it is applied
 * to. Reference it in a route with the {@code Audit} filter name.
 */
public class AuditGatewayFilterFactory extends AbstractGatewayFilterFactory<AuditGatewayFilterFactory.Config> {

	private static final Logger LOG = LoggerFactory.getLogger(AuditGatewayFilterFactory.class);

	private final AuditEventFactory eventFactory;

	private final AuditEventPublisher publisher;

	/**
	 * Create a new factory.
	 * @param eventFactory the factory building the audit event
	 * @param publisher the publisher delivering the audit event
	 */
	public AuditGatewayFilterFactory(AuditEventFactory eventFactory, AuditEventPublisher publisher) {
		super(Config.class);
		this.eventFactory = eventFactory;
		this.publisher = publisher;
	}

	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> chain.filter(exchange)
			.then(Mono.defer(
					() -> this.eventFactory.create(exchange).doOnNext(this.publisher::publish).onErrorResume((ex) -> {
						LOG.warn("audit event publication failed", ex);
						return Mono.empty();
					}).then()));
	}

	/**
	 * Configuration for {@link AuditGatewayFilterFactory}. The audited groups are
	 * configured globally, so this filter takes no argument.
	 */
	public static class Config {

	}

}
