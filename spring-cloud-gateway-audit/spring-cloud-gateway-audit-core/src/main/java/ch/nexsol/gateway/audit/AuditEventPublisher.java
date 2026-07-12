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

/**
 * Extension point delivering an {@link AuditEvent} to an audit backend such as Redis,
 * Kafka, RabbitMQ or a database. Provide a bean implementing this interface to override
 * the {@link DefaultAuditEventPublisher default} publisher.
 * <p>
 * Called once the response has been produced. Implementations must not block the event
 * loop; offload to their own scheduler when the backend performs blocking I/O.
 */
@FunctionalInterface
public interface AuditEventPublisher {

	/**
	 * Publish the given audit event to the backend.
	 * @param event the event to publish
	 */
	void publish(AuditEvent event);

}
