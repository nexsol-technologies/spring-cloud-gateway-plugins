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

package ch.nexsol.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;

/**
 * Default {@link AuditEventPublisher} used when the application does not provide its own.
 * It logs the event at debug level and republishes it as an {@link AuditApplicationEvent}
 * so a listener can forward it to Redis, Kafka, RabbitMQ, a database, etc.
 */
public class DefaultAuditEventPublisher implements AuditEventPublisher {

	private static final Logger LOG = LoggerFactory.getLogger(DefaultAuditEventPublisher.class);

	private final ApplicationEventPublisher applicationEventPublisher;

	/**
	 * Create a new publisher.
	 * @param applicationEventPublisher the Spring event publisher used to republish the
	 * audit event
	 */
	public DefaultAuditEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
		this.applicationEventPublisher = applicationEventPublisher;
	}

	@Override
	public void publish(AuditEvent event) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("audit {}", event.attributes());
		}
		this.applicationEventPublisher.publishEvent(new AuditApplicationEvent(this, event));
	}

}
