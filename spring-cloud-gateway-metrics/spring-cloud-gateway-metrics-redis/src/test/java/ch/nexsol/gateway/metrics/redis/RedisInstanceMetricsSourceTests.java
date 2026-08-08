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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetric.InstanceInstrumentation;
import ch.nexsol.gateway.metrics.InstanceMetric.JvmStats;
import ch.nexsol.gateway.metrics.InstanceMetric.NettyStats;
import ch.nexsol.gateway.metrics.InstanceMetric.SystemStats;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.ScanOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RedisInstanceMetricsSource}.
 */
class RedisInstanceMetricsSourceTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private ReactiveStringRedisTemplate redisTemplate;

	private ReactiveValueOperations<String, String> valueOperations;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.redisTemplate = mock(ReactiveStringRedisTemplate.class);
		this.valueOperations = mock(ReactiveValueOperations.class);
		when(this.redisTemplate.opsForValue()).thenReturn(this.valueOperations);
	}

	private void published(Map<String, String> byKey) {
		when(this.redisTemplate.scan(any(ScanOptions.class))).thenReturn(Flux.fromIterable(byKey.keySet()));
		when(this.valueOperations.get(anyString()))
			.thenAnswer((invocation) -> Mono.justOrEmpty(byKey.get(invocation.getArgument(0, String.class))));
	}

	private String payload(InstanceMetric metric) throws Exception {
		return this.objectMapper.writeValueAsString(metric);
	}

	private static InstanceMetric metric(String instanceId, long heapUsed) {
		return new InstanceMetric(instanceId, null, 180, new JvmStats(heapUsed, 4000, 50, 0.01, 12.0, 3, 40, 55, 20),
				new SystemStats(0.2, 0.4, 1.5, 8, 100, 1024), new NettyStats(0, 8), List.of(),
				new InstanceInstrumentation(true, true));
	}

	private RedisInstanceMetricsSource source() {
		return new RedisInstanceMetricsSource(this.redisTemplate, new RedisMetricsProperties(), this.objectMapper);
	}

	@Test
	void keepsOneRowPerInstanceRatherThanMergingThem() throws Exception {
		Map<String, String> byKey = new LinkedHashMap<>();
		byKey.put("gateway:instances:pod-b", payload(metric("pod-b", 2000)));
		byKey.put("gateway:instances:pod-a", payload(metric("pod-a", 1000)));
		published(byKey);

		InstanceMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.coverage()).isEqualTo("2 instances, via Redis");
		assertThat(snapshot.instances()).extracting(InstanceMetric::instanceId).containsExactly("pod-a", "pod-b");
		assertThat(snapshot.instances()).extracting((instance) -> instance.jvm().heapUsedBytes())
			.containsExactly(1000L, 2000L);
	}

	@Test
	void roundTripsTheWholePayload() throws Exception {
		published(Map.of("gateway:instances:pod-a", payload(metric("pod-a", 1000))));

		InstanceMetric read = source().collect().block().instances().get(0);

		assertThat(read.uptimeSeconds()).isEqualTo(180);
		assertThat(read.system().cpuCount()).isEqualTo(8);
		assertThat(read.netty().eventLoops()).isEqualTo(8);
		assertThat(read.instrumentation().connectionPool()).isTrue();
	}

	@Test
	void ignoresAnUnreadableEntryRatherThanLosingTheOthers() throws Exception {
		Map<String, String> byKey = new LinkedHashMap<>();
		byKey.put("gateway:instances:pod-a", payload(metric("pod-a", 1000)));
		byKey.put("gateway:instances:pod-legacy", "{\"written\":\"by an older version\"");
		published(byKey);

		InstanceMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.instances()).hasSize(1);
		assertThat(snapshot.coverage()).isEqualTo("1 instance, via Redis");
	}

	@Test
	void saysSoWhenNobodyPublishedYet() {
		published(Map.of());

		InstanceMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.instances()).isEmpty();
		assertThat(snapshot.coverage()).isEqualTo("no instance published its figures yet");
	}

	@Test
	void reportsRedisBeingUnreachableRatherThanFailing() {
		when(this.redisTemplate.scan(any(ScanOptions.class))).thenReturn(Flux.error(new IllegalStateException("down")));

		InstanceMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.instances()).isEmpty();
		assertThat(snapshot.coverage()).isEqualTo("Redis unavailable");
	}

	@Test
	void doesNotShareANamespaceWithTheRouteFigures() {
		RedisMetricsProperties properties = new RedisMetricsProperties();

		// The route source scans keyPrefix + "*". An instance prefix nested under it
		// would come back in that scan and be discarded, one warning per entry, on every
		// single refresh.
		assertThat(properties.getInstanceKeyPrefix()).doesNotStartWith(properties.getKeyPrefix());
		assertThat(properties.getKeyPrefix()).doesNotStartWith(properties.getInstanceKeyPrefix());
	}

}
