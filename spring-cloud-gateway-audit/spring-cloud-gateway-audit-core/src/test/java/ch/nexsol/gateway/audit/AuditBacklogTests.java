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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AuditBacklog}.
 */
class AuditBacklogTests {

	private static final Duration NO_TIMEOUT = Duration.ZERO;

	private final AtomicInteger sent = new AtomicInteger();

	@AfterEach
	void restoreTheClock() {
		VirtualTimeScheduler.reset();
	}

	@Test
	void sendsEveryEventWhileTheBackendKeepsUp() {
		AuditBacklog backlog = new AuditBacklog("test", 2);

		for (int i = 0; i < 10; i++) {
			backlog.submit(() -> sending(Mono.just(1L)), NO_TIMEOUT);
		}

		assertThat(this.sent).hasValue(10);
		assertThat(backlog.pending()).isZero();
		assertThat(backlog.dropped()).isZero();
		assertThat(backlog.failed()).isZero();
	}

	@Test
	void dropsAndCountsWhatExceedsTheLimit() {
		AuditBacklog backlog = new AuditBacklog("test", 2);

		for (int i = 0; i < 5; i++) {
			backlog.submit(() -> sending(Mono.never()), NO_TIMEOUT);
		}

		assertThat(this.sent).hasValue(2);
		assertThat(backlog.pending()).isEqualTo(2);
		assertThat(backlog.dropped()).isEqualTo(3);
	}

	@Test
	void doesNotBuildTheSendOfAnEventItDrops() {
		AuditBacklog backlog = new AuditBacklog("test", 1);
		backlog.submit(Mono::never, NO_TIMEOUT);

		backlog.submit(() -> {
			throw new AssertionError("built for an event that is dropped");
		}, NO_TIMEOUT);

		assertThat(backlog.dropped()).isEqualTo(1);
		assertThat(backlog.failed()).isZero();
	}

	@Test
	void takesEventsAgainOnceThePendingOnesComplete() {
		AuditBacklog backlog = new AuditBacklog("test", 1);
		Sinks.One<Long> pending = Sinks.one();
		backlog.submit(pending::asMono, NO_TIMEOUT);
		backlog.submit(() -> sending(Mono.just(1L)), NO_TIMEOUT);
		assertThat(this.sent).hasValue(0);

		pending.tryEmitValue(1L);
		backlog.submit(() -> sending(Mono.just(1L)), NO_TIMEOUT);

		assertThat(this.sent).hasValue(1);
		assertThat(backlog.pending()).isZero();
	}

	@Test
	void givesThePlaceBackAndCountsASendThatFails() {
		AuditBacklog backlog = new AuditBacklog("test", 1);

		backlog.submit(() -> Mono.error(new IllegalStateException("backend is down")), NO_TIMEOUT);
		backlog.submit(() -> sending(Mono.just(1L)), NO_TIMEOUT);

		assertThat(backlog.failed()).isEqualTo(1);
		assertThat(backlog.dropped()).isZero();
		assertThat(this.sent).hasValue(1);
	}

	@Test
	void givesThePlaceBackAndCountsASendThatCannotEvenBeBuilt() {
		AuditBacklog backlog = new AuditBacklog("test", 1);

		backlog.submit(() -> {
			throw new IllegalStateException("cannot serialise");
		}, NO_TIMEOUT);

		assertThat(backlog.failed()).isEqualTo(1);
		assertThat(backlog.pending()).isZero();
	}

	@Test
	void givesThePlaceBackOfASendThatOutlastsTheTimeout() {
		VirtualTimeScheduler clock = VirtualTimeScheduler.getOrSet();
		AuditBacklog backlog = new AuditBacklog("test", 1);
		backlog.submit(Mono::never, Duration.ofSeconds(30));
		assertThat(backlog.pending()).isEqualTo(1);

		clock.advanceTimeBy(Duration.ofSeconds(31));

		// Without the timeout a backend that never answers keeps the place for good, and
		// every later event is dropped even once it is back.
		assertThat(backlog.pending()).isZero();
		assertThat(backlog.failed()).isEqualTo(1);
	}

	@Test
	void holdsEverythingWhenNoLimitIsSet() {
		AuditBacklog backlog = new AuditBacklog("test", 0);

		for (int i = 0; i < 1000; i++) {
			backlog.submit(() -> sending(Mono.never()), NO_TIMEOUT);
		}

		assertThat(this.sent).hasValue(1000);
		assertThat(backlog.dropped()).isZero();
	}

	private Mono<Long> sending(Mono<Long> outcome) {
		this.sent.incrementAndGet();
		return outcome;
	}

}
