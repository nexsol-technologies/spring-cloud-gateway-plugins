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

package ch.nexsol.gateway.servicegraph.redis;

import java.util.LinkedHashMap;
import java.util.Map;

import ch.nexsol.gateway.servicegraph.GraphEdge;
import ch.nexsol.gateway.servicegraph.ServiceGraphSnapshot;
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
 * Tests for {@link RedisServiceGraphSource}.
 */
class RedisServiceGraphSourceTests {

	private final Map<String, String> keyspace = new LinkedHashMap<>();

	private ReactiveStringRedisTemplate redisTemplate;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.redisTemplate = mock(ReactiveStringRedisTemplate.class);
		ReactiveValueOperations<String, String> valueOperations = mock(ReactiveValueOperations.class);
		when(this.redisTemplate.scan(any(ScanOptions.class)))
			.thenAnswer((invocation) -> Flux.fromIterable(this.keyspace.keySet()));
		when(this.redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(anyString()))
			.thenAnswer((invocation) -> Mono.justOrEmpty(this.keyspace.get(invocation.getArgument(0).toString())));
	}

	@Test
	void sumsWhatEveryInstancePublished() throws Exception {
		publish("pod-a", new GraphEdge("web", "orders", "orders-route", 3, 1));
		publish("pod-b", new GraphEdge("web", "orders", "orders-route", 4, 0));

		ServiceGraphSnapshot snapshot = source().collect().block();

		assertThat(snapshot.edges()).containsExactly(new GraphEdge("web", "orders", "orders-route", 7, 1));
	}

	@Test
	void namesHowManyInstancesTheGraphCovers() throws Exception {
		publish("pod-a", new GraphEdge("web", "orders", "orders-route", 1, 0));
		publish("pod-b", new GraphEdge("web", "orders", "orders-route", 1, 0));

		assertThat(source().collect().block().coverage()).isEqualTo("2 instances, via Redis");
	}

	@Test
	void saysSoWhenNoInstancePublishedYet() {
		assertThat(source().collect().block().coverage()).isEqualTo("no instance published its graph yet");
	}

	@Test
	void ignoresAnUnreadableEntryRatherThanTheWholeGraph() throws Exception {
		publish("pod-a", new GraphEdge("web", "orders", "orders-route", 3, 0));
		this.keyspace.put("gateway:service-graph:pod-b", "not json at all");

		ServiceGraphSnapshot snapshot = source().collect().block();

		assertThat(snapshot.edges()).containsExactly(new GraphEdge("web", "orders", "orders-route", 3, 0));
		assertThat(snapshot.coverage()).isEqualTo("1 instance, via Redis");
	}

	@Test
	void reportsRedisUnavailableRatherThanFailing() {
		when(this.redisTemplate.scan(any(ScanOptions.class)))
			.thenReturn(Flux.error(new IllegalStateException("redis is down")));

		ServiceGraphSnapshot snapshot = source().collect().block();

		assertThat(snapshot.coverage()).isEqualTo("Redis unavailable");
		assertThat(snapshot.edges()).isEmpty();
	}

	private void publish(String instanceId, GraphEdge... edges) throws Exception {
		this.keyspace.put("gateway:service-graph:" + instanceId, new ObjectMapper().writeValueAsString(edges));
	}

	private RedisServiceGraphSource source() {
		return new RedisServiceGraphSource(this.redisTemplate, new RedisServiceGraphProperties(), new ObjectMapper());
	}

}
