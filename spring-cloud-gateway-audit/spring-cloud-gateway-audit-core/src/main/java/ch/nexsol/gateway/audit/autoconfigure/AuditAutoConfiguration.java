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

package ch.nexsol.gateway.audit.autoconfigure;

import ch.nexsol.gateway.audit.AuditEventFactory;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.AuditProperties;
import ch.nexsol.gateway.audit.DefaultAuditEventPublisher;
import ch.nexsol.gateway.audit.factory.AuditGatewayFilterFactory;
import ch.nexsol.gateway.audit.webfilter.AuditWebFilter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration registering the auditing plugin: the shared event factory, a default
 * publisher, the per-route {@code Audit} gateway filter factory and, when enabled, the
 * global auditing web filter.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.audit.enabled", matchIfMissing = true)
public class AuditAutoConfiguration {

	/**
	 * Binds the audit properties.
	 * @return the audit properties bean
	 */
	@Bean
	@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.audit")
	AuditProperties auditProperties() {
		return new AuditProperties();
	}

	/**
	 * Registers the factory building audit events from an exchange.
	 * @param properties the audit properties
	 * @return the audit event factory bean
	 */
	@Bean
	AuditEventFactory auditEventFactory(AuditProperties properties) {
		return new AuditEventFactory(properties);
	}

	/**
	 * Registers the default publisher unless the application provides its own.
	 * @param applicationEventPublisher the Spring event publisher
	 * @return the default audit event publisher bean
	 */
	@Bean
	@ConditionalOnMissingBean(AuditEventPublisher.class)
	AuditEventPublisher auditEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
		return new DefaultAuditEventPublisher(applicationEventPublisher);
	}

	/**
	 * Registers the per-route {@code Audit} gateway filter factory.
	 * @param eventFactory the audit event factory
	 * @param publisher the audit event publisher
	 * @return the audit gateway filter factory bean
	 */
	@Bean
	AuditGatewayFilterFactory auditGatewayFilterFactory(AuditEventFactory eventFactory, AuditEventPublisher publisher) {
		return new AuditGatewayFilterFactory(eventFactory, publisher);
	}

	/**
	 * Registers the global auditing web filter when explicitly enabled.
	 * @param eventFactory the audit event factory
	 * @param publisher the audit event publisher
	 * @param properties the audit properties holding the excluded paths
	 * @return the audit web filter bean
	 */
	@Bean
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.audit.web-filter.enabled", havingValue = "true")
	AuditWebFilter auditWebFilter(AuditEventFactory eventFactory, AuditEventPublisher publisher,
			AuditProperties properties) {
		return new AuditWebFilter(eventFactory, publisher, properties.getWebFilter().getExcludePaths());
	}

}
