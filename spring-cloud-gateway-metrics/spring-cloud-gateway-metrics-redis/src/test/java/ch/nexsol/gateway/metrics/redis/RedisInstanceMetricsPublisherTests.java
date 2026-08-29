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
import java.util.List;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetric.InstanceInstrumentation;
import ch.nexsol.gateway.metrics.InstanceMetric.JvmStats;
import ch.nexsol.gateway.metrics.InstanceMetric.NettyStats;
import ch.nexsol.gateway.metrics.InstanceMetric.SystemStats;
import ch.nexsol.gateway.metrics.LocalInstanceMetricsSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the task publishing the technical figures of this instance into Redis.
 */
class RedisInstanceMetricsPublisherTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private ReactiveStringRedisTemplate redisTemplate;

	private ReactiveValueOperations<String, String> valueOperations;

	private LocalInstanceMetricsSource localSource;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.redisTemplate = mock(ReactiveStringRedisTemplate.class);
		this.valueOperations = mock(ReactiveValueOperations.class);
		when(this.redisTemplate.opsForValue()).thenReturn(this.valueOperations);
		when(this.valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
		this.localSource = mock(LocalInstanceMetricsSource.class);
		when(this.localSource.read()).thenReturn(metric());
	}

	private static InstanceMetric metric() {
		return new InstanceMetric("pod-a", null, 180, new JvmStats(1000, 4000, 50, 0.01, 12.0, 3, 40, 55, 20),
				new SystemStats(0.2, 0.4, 1.5, 8, 100, 1024), new NettyStats(0, 8), List.of(),
				new InstanceInstrumentation(true, true));
	}

	private RedisInstanceMetricsPublisher publisher(RedisMetricsProperties properties) {
		return new RedisInstanceMetricsPublisher(this.redisTemplate, this.localSource, properties, this.objectMapper,
				new InstanceIdentity("pod-a"));
	}

	@Test
	void writesTheFiguresUnderTheKeyOfThisInstance() {
		publisher(new RedisMetricsProperties()).publish().block();

		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		verify(this.valueOperations).set(key.capture(), anyString(), any(Duration.class));
		// The instance figures live in their own namespace, not under the route key
		// prefix.
		assertThat(key.getValue()).isEqualTo("gateway:instances:pod-a");
	}

	@Test
	void writesWhatThisInstanceRead() throws Exception {
		publisher(new RedisMetricsProperties()).publish().block();

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(this.valueOperations).set(anyString(), payload.capture(), any(Duration.class));
		InstanceMetric published = this.objectMapper.readValue(payload.getValue(), InstanceMetric.class);
		assertThat(published.instanceId()).isEqualTo("pod-a");
		assertThat(published.jvm().heapUsedBytes()).isEqualTo(1000);
		assertThat(published.system().cpuCount()).isEqualTo(8);
	}

	@Test
	void appliesTheConfiguredTimeToLiveSoAStoppedInstanceFadesOut() {
		RedisMetricsProperties properties = new RedisMetricsProperties();
		properties.setTimeToLive(Duration.ofSeconds(90));

		publisher(properties).publish().block();

		ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
		verify(this.valueOperations).set(anyString(), anyString(), ttl.capture());
		assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(90));
	}

	@Test
	void honoursTheConfiguredInstanceKeyPrefix() {
		RedisMetricsProperties properties = new RedisMetricsProperties();
		properties.setInstanceKeyPrefix("custom:instances:");

		assertThat(publisher(properties).key()).isEqualTo("custom:instances:pod-a");
	}

	@Test
	void swallowsAFailureSoTheGatewayKeepsServing() {
		when(this.valueOperations.set(anyString(), anyString(), any(Duration.class)))
			.thenReturn(Mono.error(new IllegalStateException("connection refused")));

		// Publishing metrics must never take the gateway down; the next tick retries.
		assertThat(publisher(new RedisMetricsProperties()).publish().block()).isNull();
	}

	@Test
	void swallowsAFigureItCannotSerialise() {
		when(this.localSource.read()).thenThrow(new IllegalStateException("meter registry gone"));

		assertThat(publisher(new RedisMetricsProperties()).publish().block()).isNull();
	}

	@Test
	void publishesOnTheConfiguredIntervalOnceStarted() {
		RedisMetricsProperties properties = new RedisMetricsProperties();
		properties.setPublishInterval(Duration.ofMillis(20));
		RedisInstanceMetricsPublisher publisher = publisher(properties);

		publisher.start();
		try {
			assertThat(publisher.isRunning()).isTrue();
			verify(this.valueOperations, timeout(5000).atLeast(2)).set(anyString(), anyString(), any(Duration.class));
		}
		finally {
			publisher.stop();
		}
		assertThat(publisher.isRunning()).isFalse();
	}

	@Test
	void stopsCleanlyWhenItWasNeverStarted() {
		RedisInstanceMetricsPublisher publisher = publisher(new RedisMetricsProperties());

		publisher.stop();

		assertThat(publisher.isRunning()).isFalse();
	}

}
