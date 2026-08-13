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

import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

/**
 * Reads the technical figures every instance published to Redis.
 * <p>
 * An instance that stops fades out on its own when its key expires, which is what makes
 * the list of rows a list of live instances rather than of every instance that ever ran.
 */
public class RedisInstanceMetricsSource implements InstanceMetricsSource {

	private static final Logger LOG = LoggerFactory.getLogger(RedisInstanceMetricsSource.class);

	private final ReactiveStringRedisTemplate redisTemplate;

	private final RedisMetricsProperties properties;

	private final ObjectMapper objectMapper;

	/**
	 * Creates the source reading the published figures.
	 * @param redisTemplate the reactive Redis template
	 * @param properties the Redis configuration
	 * @param objectMapper the mapper reading the published figures
	 */
	public RedisInstanceMetricsSource(ReactiveStringRedisTemplate redisTemplate, RedisMetricsProperties properties,
			ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	public Mono<InstanceMetricsSnapshot> collect() {
		// Deferred so the counter belongs to one subscription. Created at assembly time
		// it would be shared by every subscriber, and a second reading of the same mono
		// would report twice the instances.
		return Mono.defer(() -> {
			AtomicInteger instances = new AtomicInteger();
			ScanOptions options = ScanOptions.scanOptions()
				.match(this.properties.getInstanceKeyPrefix() + "*")
				.count(100)
				.build();
			return this.redisTemplate.scan(options)
				.flatMap((key) -> this.redisTemplate.opsForValue().get(key))
				.flatMap((payload) -> read(payload).doOnNext((metric) -> instances.incrementAndGet()))
				.sort(Comparator.comparing(InstanceMetric::instanceId))
				.collectList()
				.map((rows) -> new InstanceMetricsSnapshot(coverage(instances.get()), rows));
		}).onErrorResume((ex) -> {
			LOG.warn("Could not read the instance metrics from Redis: {}", ex.getMessage());
			return Mono.just(InstanceMetricsSnapshot.empty("Redis unavailable"));
		});
	}

	private String coverage(int instances) {
		if (instances == 0) {
			return "no instance published its figures yet";
		}
		return instances + ((instances == 1) ? " instance" : " instances") + ", via Redis";
	}

	private Flux<InstanceMetric> read(String payload) {
		try {
			return Flux.just(this.objectMapper.readValue(payload, InstanceMetric.class));
		}
		catch (Exception ex) {
			// A key written by an older version, or by something else entirely, must not
			// cost the figures of every other instance.
			LOG.warn("Ignoring an unreadable instance metrics entry: {}", ex.getMessage());
			return Flux.empty();
		}
	}

}
