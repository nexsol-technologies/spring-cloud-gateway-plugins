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

package ch.nexsol.gateway.audit.kafka;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaAuditEventPublisherTests {

	@Test
	@SuppressWarnings("unchecked")
	void sendsSerializedEventToTopic() {
		KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
		given(template.send(anyString(), any())).willReturn(CompletableFuture.completedFuture(null));
		KafkaAuditEventPublisher publisher = new KafkaAuditEventPublisher(template, "gateway-audit",
				new AuditEventSerializer(new ObjectMapper()));

		publisher.publish(new AuditEvent(Instant.now(), Map.of("request.path", "/book")));

		ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
		verify(template).send(eq("gateway-audit"), payload.capture());
		assertThat(payload.getValue()).asString().contains("/book");
	}

}
