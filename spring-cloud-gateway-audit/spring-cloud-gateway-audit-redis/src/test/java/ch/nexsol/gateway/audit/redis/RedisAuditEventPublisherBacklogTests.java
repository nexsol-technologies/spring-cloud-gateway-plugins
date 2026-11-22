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

package ch.nexsol.gateway.audit.redis;

import java.time.Instant;
import java.util.Map;

import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests that the events held for a Redis that stopped answering are bounded. The client
 * queues every command while it is disconnected, without limit: one event per request,
 * each with its payload, until the heap is gone.
 */
class RedisAuditEventPublisherBacklogTests {

	private static final int DEFAULT_LIMIT = 10_000;

	private final ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);

	private final RedisAuditEventPublisher publisher = new RedisAuditEventPublisher(this.template, "gateway-audit",
			new AuditEventSerializer(new ObjectMapper()));

	@Test
	void stopsSendingOnceTooManyEventsArePending() {
		given(this.template.convertAndSend(anyString(), anyString())).willReturn(Mono.never());

		for (int i = 0; i < DEFAULT_LIMIT + 50; i++) {
			this.publisher.publish(event());
		}

		verify(this.template, atMost(DEFAULT_LIMIT)).convertAndSend(anyString(), anyString());
	}

	@Test
	void keepsSendingWhileRedisAnswers() {
		given(this.template.convertAndSend(anyString(), anyString())).willReturn(Mono.just(1L));

		for (int i = 0; i < DEFAULT_LIMIT + 50; i++) {
			this.publisher.publish(event());
		}

		verify(this.template, times(DEFAULT_LIMIT + 50)).convertAndSend(anyString(), anyString());
	}

	@Test
	void keepsSendingAfterFailures() {
		given(this.template.convertAndSend(anyString(), anyString()))
			.willReturn(Mono.error(new IllegalStateException("redis is down")));

		for (int i = 0; i < DEFAULT_LIMIT + 50; i++) {
			this.publisher.publish(event());
		}

		verify(this.template, times(DEFAULT_LIMIT + 50)).convertAndSend(anyString(), anyString());
	}

	@Test
	void honoursTheConfiguredLimitAndCountsWhatItDrops() {
		given(this.template.convertAndSend(anyString(), anyString())).willReturn(Mono.never());
		RedisAuditProperties properties = new RedisAuditProperties();
		properties.setMaxPending(2);
		RedisAuditEventPublisher bounded = new RedisAuditEventPublisher(this.template,
				new AuditEventSerializer(new ObjectMapper()), properties);

		for (int i = 0; i < 5; i++) {
			bounded.publish(event());
		}

		verify(this.template, times(2)).convertAndSend(anyString(), anyString());
		assertThat(bounded.backlog().dropped()).isEqualTo(3);
	}

	private static AuditEvent event() {
		return new AuditEvent(Instant.now(), Map.of("request.path", "/book"));
	}

}
