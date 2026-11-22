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

package ch.nexsol.gateway.routes.configserver;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that the polling of the routes outlives a source that stops answering. The ticks
 * keep coming while a read is pending, and a tick nobody asked for ends an interval with
 * an overflow error: the routes are then never read again until the gateway is restarted.
 */
class RouteConfigServerLifecycleTests {

	private static final Duration INTERVAL = Duration.ofSeconds(30);

	private VirtualTimeScheduler clock;

	private ConfigServerRouteDefinitionLocator locator;

	@BeforeEach
	void setUp() {
		this.clock = VirtualTimeScheduler.getOrSet();
		this.locator = mock(ConfigServerRouteDefinitionLocator.class);
	}

	@AfterEach
	void tearDown() {
		VirtualTimeScheduler.reset();
	}

	@Test
	void keepsPollingAfterAReadThatOutlastedManyTicks() {
		Sinks.Empty<Void> pending = Sinks.empty();
		when(this.locator.refresh()).thenReturn(Mono.empty(), pending.asMono(), Mono.empty());
		RouteConfigServerLifecycle lifecycle = new RouteConfigServerLifecycle(this.locator, INTERVAL);

		lifecycle.start();
		try {
			// The first poll hangs for forty ticks, more than an interval can hold back.
			this.clock.advanceTimeBy(INTERVAL.multipliedBy(40));
			// The read at startup, the one that hung, and the one tick held back, served
			// once the source answers: the others missed meanwhile were dropped, not
			// queued
			// and replayed in a burst.
			pending.tryEmitEmpty();
			verify(this.locator, times(3)).refresh();

			// Then one read per tick again.
			this.clock.advanceTimeBy(INTERVAL);
			verify(this.locator, times(4)).refresh();
		}
		finally {
			lifecycle.stop();
		}
	}

	@Test
	void pollsOnEveryTickWhileTheSourceAnswers() {
		when(this.locator.refresh()).thenReturn(Mono.empty());
		RouteConfigServerLifecycle lifecycle = new RouteConfigServerLifecycle(this.locator, INTERVAL);

		lifecycle.start();
		try {
			this.clock.advanceTimeBy(INTERVAL.multipliedBy(3));

			verify(this.locator, times(4)).refresh();
		}
		finally {
			lifecycle.stop();
		}
	}

	@Test
	void readsOnceAndDoesNotPollWithoutAnInterval() {
		when(this.locator.refresh()).thenReturn(Mono.empty());
		RouteConfigServerLifecycle lifecycle = new RouteConfigServerLifecycle(this.locator, Duration.ZERO);

		lifecycle.start();
		try {
			this.clock.advanceTimeBy(INTERVAL.multipliedBy(3));

			verify(this.locator, times(1)).refresh();
		}
		finally {
			lifecycle.stop();
		}
	}

}
