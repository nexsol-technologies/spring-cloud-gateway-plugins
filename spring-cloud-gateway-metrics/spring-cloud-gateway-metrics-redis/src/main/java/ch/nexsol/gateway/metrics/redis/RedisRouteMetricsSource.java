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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.nexsol.gateway.metrics.RouteMetric;
import ch.nexsol.gateway.metrics.RouteMetricsAggregator;
import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

/**
 * Consolidates the figures every instance published into Redis.
 * <p>
 * Reading is cheap and needs no instance to be reachable: an instance that is busy, or
 * behind a closed network, still counts through what it last published. The figures lag
 * by at most one publish interval, which is the trade for not calling anyone.
 * <p>
 * Keys are listed with {@code SCAN} rather than {@code KEYS}, so a large keyspace is not
 * blocked while the traffic view refreshes.
 */
public class RedisRouteMetricsSource implements RouteMetricsSource {

	private static final Logger LOG = LoggerFactory.getLogger(RedisRouteMetricsSource.class);

	private static final TypeReference<List<RouteMetric>> METRIC_LIST = new TypeReference<>() {
	};

	private final ReactiveStringRedisTemplate redisTemplate;

	private final RedisMetricsProperties properties;

	private final ObjectMapper objectMapper;

	/**
	 * Creates the source reading the published figures.
	 * @param redisTemplate the reactive Redis template
	 * @param properties the Redis configuration
	 * @param objectMapper the mapper reading the published figures
	 */
	public RedisRouteMetricsSource(ReactiveStringRedisTemplate redisTemplate, RedisMetricsProperties properties,
			ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	public Mono<RouteMetricsSnapshot> collect() {
		AtomicInteger instances = new AtomicInteger();
		ScanOptions options = ScanOptions.scanOptions().match(this.properties.getKeyPrefix() + "*").count(100).build();
		return this.redisTemplate.scan(options)
			.flatMap((key) -> this.redisTemplate.opsForValue().get(key))
			.flatMap((payload) -> read(payload).doOnNext((metrics) -> instances.incrementAndGet()))
			.flatMapIterable((metrics) -> metrics)
			.collectList()
			.map((partials) -> new RouteMetricsSnapshot(coverage(instances.get()),
					RouteMetricsAggregator.merge(partials)))
			.onErrorResume((ex) -> {
				LOG.warn("Could not read the route metrics from Redis: {}", ex.getMessage());
				return Mono.just(RouteMetricsSnapshot.empty("Redis unavailable"));
			});
	}

	private String coverage(int instances) {
		if (instances == 0) {
			return "no instance published its figures yet";
		}
		return instances + ((instances == 1) ? " instance" : " instances") + ", via Redis";
	}

	private Flux<List<RouteMetric>> read(String payload) {
		try {
			return Flux.just(this.objectMapper.readValue(payload, METRIC_LIST));
		}
		catch (Exception ex) {
			// A key written by an older version, or by something else entirely, must not
			// cost the figures of every other instance.
			LOG.warn("Ignoring an unreadable route metrics entry: {}", ex.getMessage());
			return Flux.empty();
		}
	}

}
