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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.audit.AuditAttributes;
import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecordingAuditEventPublisherTests {

	private final AuditTailBuffer buffer = new AuditTailBuffer();

	private final List<AuditEvent> delivered = new ArrayList<>();

	@Test
	void recordsTheEventAndStillDeliversItToTheRealPublisher() {
		AuditEvent event = event("/api/orders");

		new RecordingAuditEventPublisher(this.delivered::add, this.buffer).publish(event);

		assertThat(this.delivered).containsExactly(event);
		assertThat(this.buffer.snapshot()).extracting(AuditEventView::path).containsExactly("/api/orders");
	}

	@Test
	void keepsTheEventInTheTailEvenWhenTheBackendRejectsIt() {
		AuditEventPublisher failing = (event) -> {
			throw new IllegalStateException("backend down");
		};

		assertThatIllegalStateException()
			.isThrownBy(() -> new RecordingAuditEventPublisher(failing, this.buffer).publish(event("/api/orders")));

		assertThat(this.buffer.snapshot()).hasSize(1);
	}

	@Test
	void wrapsThePublisherFoundInTheContext() {
		AuditEventPublisher publisher = this.delivered::add;

		Object decorated = postProcessorOver(this.buffer).postProcessAfterInitialization(publisher, "auditPublisher");

		assertThat(decorated).isInstanceOf(RecordingAuditEventPublisher.class);
		((AuditEventPublisher) decorated).publish(event("/api/orders"));
		assertThat(this.delivered).hasSize(1);
		assertThat(this.buffer.snapshot()).hasSize(1);
	}

	@Test
	void leavesEveryOtherBeanAloneAndNeverWrapsTwice() {
		AuditTailBeanPostProcessor postProcessor = postProcessorOver(this.buffer);
		Object unrelated = "not a publisher";
		RecordingAuditEventPublisher alreadyWrapped = new RecordingAuditEventPublisher(this.delivered::add,
				this.buffer);

		assertThat(postProcessor.postProcessAfterInitialization(unrelated, "unrelated")).isSameAs(unrelated);
		assertThat(postProcessor.postProcessAfterInitialization(alreadyWrapped, "auditPublisher"))
			.isSameAs(alreadyWrapped);
	}

	@Test
	void leavesThePublisherUntouchedWhenNoTailIsAvailable() {
		AuditEventPublisher publisher = this.delivered::add;

		assertThat(postProcessorOver(null).postProcessAfterInitialization(publisher, "auditPublisher"))
			.isSameAs(publisher);
	}

	@SuppressWarnings("unchecked")
	private static AuditTailBeanPostProcessor postProcessorOver(AuditTailBuffer buffer) {
		ObjectProvider<AuditTailBuffer> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(buffer);
		return new AuditTailBeanPostProcessor(provider);
	}

	private static AuditEvent event(String path) {
		return new AuditEvent(Instant.now(), Map.of(AuditAttributes.REQUEST_PATH, path));
	}

}
