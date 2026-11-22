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

package ch.nexsol.gateway.metrics.redis;

import java.time.Duration;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.metrics.LocalRouteMetricsSource;
import ch.nexsol.gateway.metrics.MetricsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that the periodic publication outlives a Redis that stops answering. The ticks
 * keep coming while a write is pending, and a tick nobody asked for ends an interval with
 * an overflow error: the publication then stops until the gateway is restarted.
 */
class RedisRouteMetricsPublisherStallTests {

	private VirtualTimeScheduler clock;

	private ReactiveStringRedisTemplate redisTemplate;

	private ReactiveValueOperations<String, String> valueOperations;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.clock = VirtualTimeScheduler.getOrSet();
		this.redisTemplate = mock(ReactiveStringRedisTemplate.class);
		this.valueOperations = mock(ReactiveValueOperations.class);
		when(this.redisTemplate.opsForValue()).thenReturn(this.valueOperations);
	}

	@AfterEach
	void tearDown() {
		VirtualTimeScheduler.reset();
	}

	@Test
	void keepsPublishingAfterAWriteThatOutlastedManyTicks() {
		RedisMetricsProperties properties = new RedisMetricsProperties();
		Duration interval = properties.getPublishInterval();
		Sinks.One<Boolean> pending = Sinks.one();
		when(this.valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(pending.asMono(),
				Mono.just(true));
		RedisRouteMetricsPublisher publisher = publisher(properties);

		publisher.start();
		try {
			// The first write hangs for forty ticks, more than an interval can hold back.
			this.clock.advanceTimeBy(interval.multipliedBy(40));
			assertThat(publisher.isRunning()).isTrue();

			// Once Redis answers, the one tick held back is served: the others missed
			// meanwhile were dropped, not queued and replayed in a burst.
			pending.tryEmitValue(true);
			verify(this.valueOperations, times(2)).set(anyString(), anyString(), any(Duration.class));

			// Then one write per tick again.
			this.clock.advanceTimeBy(interval);
			verify(this.valueOperations, times(3)).set(anyString(), anyString(), any(Duration.class));
			assertThat(publisher.isRunning()).isTrue();
		}
		finally {
			publisher.stop();
		}
	}

	@Test
	void publishesOnEveryTickWhileRedisAnswers() {
		RedisMetricsProperties properties = new RedisMetricsProperties();
		when(this.valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
		RedisRouteMetricsPublisher publisher = publisher(properties);

		publisher.start();
		try {
			this.clock.advanceTimeBy(properties.getPublishInterval().multipliedBy(3));

			verify(this.valueOperations, times(3)).set(anyString(), anyString(), any(Duration.class));
		}
		finally {
			publisher.stop();
		}
	}

	@SuppressWarnings("unchecked")
	private RedisRouteMetricsPublisher publisher(RedisMetricsProperties properties) {
		ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
		LocalRouteMetricsSource localSource = new LocalRouteMetricsSource(provider, new MetricsProperties(),
				new InstanceIdentity("pod-a"));
		return new RedisRouteMetricsPublisher(this.redisTemplate, localSource, properties, new ObjectMapper(),
				new InstanceIdentity("pod-a"));
	}

}
