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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import ch.nexsol.gateway.audit.AuditEvent;

/**
 * Bounded, in-memory tail of the most recent audit events, newest first.
 * <p>
 * The buffer is what the audit view reads: audit events are pushed to their configured
 * backend (Redis, Kafka, a database, ...) for keeping, and a copy of the last few is kept
 * here so the gateway can show its own recent traffic without querying that backend.
 * Older events are dropped once the buffer is full, and nothing survives a restart.
 */
public class AuditTailBuffer {

	/** Number of events kept in memory. */
	public static final int CAPACITY = 500;

	private final Deque<AuditEventView> events = new ArrayDeque<>(CAPACITY);

	/**
	 * Records an event, dropping the oldest one when the buffer is full. Called on the
	 * event loop as part of publishing, so it only ever touches memory.
	 * @param event the audit event to record
	 */
	public void record(AuditEvent event) {
		AuditEventView view = AuditEventView.of(event);
		synchronized (this.events) {
			if (this.events.size() >= CAPACITY) {
				this.events.removeLast();
			}
			this.events.addFirst(view);
		}
	}

	/**
	 * Returns the buffered events, newest first.
	 * @return an immutable snapshot of the buffer
	 */
	public List<AuditEventView> snapshot() {
		synchronized (this.events) {
			return List.copyOf(this.events);
		}
	}

}
