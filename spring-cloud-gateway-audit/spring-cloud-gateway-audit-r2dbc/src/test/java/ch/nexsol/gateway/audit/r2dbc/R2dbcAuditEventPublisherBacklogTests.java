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

package ch.nexsol.gateway.audit.r2dbc;

import java.time.Instant;
import java.util.Map;

import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.r2dbc.core.DatabaseClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests that the events held for a database slower than the traffic are bounded. The
 * connection pool has no acquisition timeout by default, so every insert waits its turn
 * for as long as it takes: one per request, each with its payload, until the heap is
 * gone.
 */
class R2dbcAuditEventPublisherBacklogTests {

	private static final int DEFAULT_LIMIT = 10_000;

	private final DatabaseClient databaseClient = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);

	private final R2dbcAuditEventPublisher publisher = new R2dbcAuditEventPublisher(this.databaseClient, "audit_event",
			new AuditEventSerializer(new ObjectMapper()));

	@Test
	void stopsInsertingOnceTooManyEventsArePending() {
		inserting(Mono.never());

		for (int i = 0; i < DEFAULT_LIMIT + 50; i++) {
			this.publisher.publish(event());
		}

		verify(this.databaseClient, atMost(DEFAULT_LIMIT)).sql(anyString());
	}

	@Test
	void keepsInsertingWhileTheDatabaseAnswers() {
		inserting(Mono.just(1L));

		for (int i = 0; i < DEFAULT_LIMIT + 50; i++) {
			this.publisher.publish(event());
		}

		verify(this.databaseClient, times(DEFAULT_LIMIT + 50)).sql(anyString());
	}

	@Test
	void keepsInsertingAfterFailures() {
		inserting(Mono.error(new IllegalStateException("database is down")));

		for (int i = 0; i < DEFAULT_LIMIT + 50; i++) {
			this.publisher.publish(event());
		}

		verify(this.databaseClient, times(DEFAULT_LIMIT + 50)).sql(anyString());
	}

	@Test
	void honoursTheConfiguredLimitAndCountsWhatItDrops() {
		inserting(Mono.never());
		R2dbcAuditProperties properties = new R2dbcAuditProperties();
		properties.setMaxPending(2);
		R2dbcAuditEventPublisher bounded = new R2dbcAuditEventPublisher(this.databaseClient,
				new AuditEventSerializer(new ObjectMapper()), properties);

		for (int i = 0; i < 5; i++) {
			bounded.publish(event());
		}

		verify(this.databaseClient, times(2)).sql(anyString());
		assertThat(bounded.backlog().dropped()).isEqualTo(3);
	}

	private void inserting(Mono<Long> outcome) {
		given(this.databaseClient.sql(anyString())
			.bind(anyString(), any())
			.bind(anyString(), any())
			.fetch()
			.rowsUpdated()).willReturn(outcome);
		// Stubbing called sql() once itself.
		clearInvocations(this.databaseClient);
	}

	private static AuditEvent event() {
		return new AuditEvent(Instant.now(), Map.of("request.path", "/book"));
	}

}
