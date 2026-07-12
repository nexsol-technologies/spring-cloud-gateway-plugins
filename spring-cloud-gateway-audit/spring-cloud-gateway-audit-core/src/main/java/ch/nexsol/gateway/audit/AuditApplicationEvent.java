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

import org.springframework.context.ApplicationEvent;

/**
 * Spring {@link ApplicationEvent} wrapping an {@link AuditEvent}. Published by
 * {@link DefaultAuditEventPublisher} so an application can forward audit events to a
 * backend with a plain {@code @EventListener} instead of replacing the publisher bean.
 */
public class AuditApplicationEvent extends ApplicationEvent {

	private final transient AuditEvent auditEvent;

	/**
	 * Create a new event.
	 * @param source the component publishing the event
	 * @param auditEvent the audited attributes
	 */
	public AuditApplicationEvent(Object source, AuditEvent auditEvent) {
		super(source);
		this.auditEvent = auditEvent;
	}

	/**
	 * @return the audited attributes
	 */
	public AuditEvent getAuditEvent() {
		return this.auditEvent;
	}

}
