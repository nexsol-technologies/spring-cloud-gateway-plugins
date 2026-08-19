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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.nexsol.gateway.servicegraph.GraphEdge;
import ch.nexsol.gateway.servicegraph.ServiceGraphSnapshot;
import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

/**
 * Consolidates the edges every instance published into Redis.
 * <p>
 * Reading is cheap and needs no instance to be reachable: an instance that is busy, or
 * behind a closed network, still counts through what it last published. The graph lags by
 * at most one publish interval, which is the trade for not calling anyone.
 * <p>
 * Keys are listed with {@code SCAN} rather than {@code KEYS}, so a large keyspace is not
 * blocked while the graph refreshes.
 */
public class RedisServiceGraphSource implements ServiceGraphSource {

	private static final Logger LOG = LoggerFactory.getLogger(RedisServiceGraphSource.class);

	private static final TypeReference<List<GraphEdge>> EDGE_LIST = new TypeReference<>() {
	};

	private final ReactiveStringRedisTemplate redisTemplate;

	private final RedisServiceGraphProperties properties;

	private final ObjectMapper objectMapper;

	/**
	 * Creates the source reading the published edges.
	 * @param redisTemplate the reactive Redis template
	 * @param properties the Redis configuration
	 * @param objectMapper the mapper reading the published edges
	 */
	public RedisServiceGraphSource(ReactiveStringRedisTemplate redisTemplate, RedisServiceGraphProperties properties,
			ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	public Mono<ServiceGraphSnapshot> collect() {
		// Deferred so the counter belongs to one subscription. Created at assembly time
		// it would be shared by every subscriber, and a second reading of the same mono
		// would report twice the instances.
		return Mono.defer(() -> {
			AtomicInteger instances = new AtomicInteger();
			ScanOptions options = ScanOptions.scanOptions()
				.match(this.properties.getKeyPrefix() + "*")
				.count(100)
				.build();
			return this.redisTemplate.scan(options)
				.flatMap((key) -> this.redisTemplate.opsForValue().get(key))
				.flatMap((payload) -> read(payload).doOnNext((edges) -> instances.incrementAndGet()))
				.flatMapIterable((edges) -> edges)
				.collectList()
				.map((partials) -> ServiceGraphSnapshot.of(coverage(instances.get()), partials));
		}).onErrorResume((ex) -> {
			LOG.warn("Could not read the service graph from Redis: {}", ex.getMessage());
			return Mono.just(ServiceGraphSnapshot.empty("Redis unavailable"));
		});
	}

	private String coverage(int instances) {
		if (instances == 0) {
			return "no instance published its graph yet";
		}
		return instances + ((instances == 1) ? " instance" : " instances") + ", via Redis";
	}

	private Flux<List<GraphEdge>> read(String payload) {
		try {
			return Flux.just(this.objectMapper.readValue(payload, EDGE_LIST));
		}
		catch (Exception ex) {
			// A key written by an older version, or by something else entirely, must not
			// cost the edges of every other instance.
			LOG.warn("Ignoring an unreadable service graph entry: {}", ex.getMessage());
			return Flux.empty();
		}
	}

}
