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

import java.time.Instant;
import java.util.Map;

import ch.nexsol.gateway.audit.AuditAttributes;
import ch.nexsol.gateway.audit.AuditEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditTailBufferTests {

	private final AuditTailBuffer buffer = new AuditTailBuffer();

	@Test
	void reportsNothingBeforeAnyExchangeIsAudited() {
		assertThat(this.buffer.snapshot()).isEmpty();
	}

	@Test
	void keepsTheEventsNewestFirst() {
		this.buffer.record(event("/first"));
		this.buffer.record(event("/second"));

		assertThat(this.buffer.snapshot()).extracting(AuditEventView::path).containsExactly("/second", "/first");
	}

	@Test
	void dropsTheOldestEventOnceFull() {
		for (int index = 0; index < AuditTailBuffer.CAPACITY + 10; index++) {
			this.buffer.record(event("/path-" + index));
		}

		assertThat(this.buffer.snapshot()).hasSize(AuditTailBuffer.CAPACITY);
		assertThat(this.buffer.snapshot().get(0).path()).isEqualTo("/path-" + (AuditTailBuffer.CAPACITY + 9));
		assertThat(this.buffer.snapshot()).extracting(AuditEventView::path).doesNotContain("/path-0");
	}

	@Test
	void handsOutASnapshotThatCannotBeMutated() {
		this.buffer.record(event("/first"));

		assertThat(this.buffer.snapshot()).isUnmodifiable();
	}

	private static AuditEvent event(String path) {
		return new AuditEvent(Instant.now(), Map.of(AuditAttributes.REQUEST_PATH, path));
	}

}
