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

import ch.nexsol.gateway.metrics.RouteMetric;
import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.ScanOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the source consolidating what every instance published into Redis.
 */
class RedisRouteMetricsSourceTests {

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

	/**
	 * Wires the mock so scanning returns the given keys and each key its payload.
	 */
	private void published(Map<String, String> byKey) {
		when(this.redisTemplate.scan(any(ScanOptions.class))).thenReturn(Flux.fromIterable(byKey.keySet()));
		when(this.valueOperations.get(anyString()))
			.thenAnswer((invocation) -> Mono.justOrEmpty(byKey.get(invocation.getArgument(0, String.class))));
	}

	private String payload(RouteMetric... metrics) throws Exception {
		return this.objectMapper.writeValueAsString(List.of(metrics));
	}

	private static RouteMetric metric(String routeId, long count, double avgMs, long errors) {
		return new RouteMetric(routeId, "http://" + routeId, count, avgMs, avgMs, 0, 0.0, errors, 0.0);
	}

	private RedisRouteMetricsSource source() {
		return new RedisRouteMetricsSource(this.redisTemplate, new RedisMetricsProperties(), this.objectMapper);
	}

	@Test
	void sumsWhatEveryInstancePublished() throws Exception {
		Map<String, String> byKey = new LinkedHashMap<>();
		byKey.put("gateway:metrics:pod-a", payload(metric("orders", 100, 10.0, 0)));
		byKey.put("gateway:metrics:pod-b", payload(metric("orders", 300, 50.0, 12)));
		published(byKey);

		RouteMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.coverage()).isEqualTo("2 instances, via Redis");
		assertThat(snapshot.metrics()).singleElement().satisfies((metric) -> {
			assertThat(metric.count()).isEqualTo(400);
			// Weighted, not the 30 ms an average of averages would give.
			assertThat(metric.avgMs()).isCloseTo(40.0, within(0.001));
			assertThat(metric.errorCount()).isEqualTo(12);
		});
	}

	@Test
	void saysSoWhenNoInstancePublishedYet() {
		published(Map.of());

		RouteMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.metrics()).isEmpty();
		assertThat(snapshot.coverage()).isEqualTo("no instance published its figures yet");
	}

	@Test
	void reportsASingleInstanceInTheSingular() throws Exception {
		published(Map.of("gateway:metrics:pod-a", payload(metric("orders", 10, 1.0, 0))));

		assertThat(source().collect().block().coverage()).isEqualTo("1 instance, via Redis");
	}

	@Test
	void keepsTheOtherInstancesWhenOneEntryIsUnreadable() throws Exception {
		Map<String, String> byKey = new LinkedHashMap<>();
		byKey.put("gateway:metrics:pod-a", "not json at all");
		byKey.put("gateway:metrics:pod-b", payload(metric("orders", 42, 5.0, 0)));
		published(byKey);

		RouteMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.metrics()).singleElement().satisfies((metric) -> assertThat(metric.count()).isEqualTo(42));
		assertThat(snapshot.coverage()).isEqualTo("1 instance, via Redis");
	}

	@Test
	void keepsDistinctRoutesApart() throws Exception {
		Map<String, String> byKey = new LinkedHashMap<>();
		byKey.put("gateway:metrics:pod-a", payload(metric("orders", 10, 1.0, 0), metric("billing", 5, 1.0, 0)));
		byKey.put("gateway:metrics:pod-b", payload(metric("orders", 10, 1.0, 0)));
		published(byKey);

		List<RouteMetric> metrics = source().collect().block().metrics();

		assertThat(metrics).extracting(RouteMetric::routeId).containsExactly("orders", "billing");
		assertThat(metrics.get(0).count()).isEqualTo(20);
	}

	@Test
	void reportsNothingRatherThanFailingWhenRedisIsDown() {
		when(this.redisTemplate.scan(any(ScanOptions.class)))
			.thenReturn(Flux.error(new IllegalStateException("connection refused")));

		RouteMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.metrics()).isEmpty();
		assertThat(snapshot.coverage()).isEqualTo("Redis unavailable");
	}

	@Test
	void scansTheConfiguredPrefixRatherThanTheWholeKeyspace() {
		published(Map.of());
		RedisMetricsProperties properties = new RedisMetricsProperties();
		properties.setKeyPrefix("custom:prefix:");

		new RedisRouteMetricsSource(this.redisTemplate, properties, this.objectMapper).collect().block();

		// KEYS would block the server; the pattern must be scoped to this gateway.
		ArgumentCaptor<ScanOptions> options = ArgumentCaptor.forClass(ScanOptions.class);
		verify(this.redisTemplate, atLeastOnce()).scan(options.capture());
		assertThat(options.getValue().getPattern()).isEqualTo("custom:prefix:*");
	}

}
