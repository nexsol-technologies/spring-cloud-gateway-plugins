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

package ch.nexsol.gateway.ui.audit;

import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventPublisher;

/**
 * {@link AuditEventPublisher} keeping a copy of every published event in the
 * {@link AuditTailBuffer} before handing it to the real publisher.
 * <p>
 * Wrapping the publisher rather than listening to an event is what makes the audit view
 * work whichever backend is configured: the default publisher, Redis, Kafka, a database
 * or an application-provided one all go through this decorator.
 */
public class RecordingAuditEventPublisher implements AuditEventPublisher {

	private final AuditEventPublisher delegate;

	private final AuditTailBuffer buffer;

	/**
	 * Creates the decorator.
	 * @param delegate the publisher actually delivering the event
	 * @param buffer the buffer keeping the recent events for the audit view
	 */
	public RecordingAuditEventPublisher(AuditEventPublisher delegate, AuditTailBuffer buffer) {
		this.delegate = delegate;
		this.buffer = buffer;
	}

	@Override
	public void publish(AuditEvent event) {
		this.buffer.record(event);
		this.delegate.publish(event);
	}

}
