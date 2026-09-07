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

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.LocalInstanceMetricsSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * Publishes the technical figures of this instance to Redis, for the instances view to
 * read them all back.
 */
public class RedisInstanceMetricsPublisher implements SmartLifecycle {

	private static final Logger LOG = LoggerFactory.getLogger(RedisInstanceMetricsPublisher.class);

	private final ReactiveStringRedisTemplate redisTemplate;

	private final LocalInstanceMetricsSource localSource;

	private final RedisMetricsProperties properties;

	private final ObjectMapper objectMapper;

	private final InstanceUri instanceUri;

	private final String key;

	private volatile Disposable subscription;

	/**
	 * Creates the publisher.
	 * @param redisTemplate the reactive Redis template
	 * @param localSource the source reading this instance's meter registry
	 * @param properties the Redis configuration
	 * @param objectMapper the mapper rendering the figures
	 * @param identity the identity of the running instance
	 * @param instanceUri where this instance is reachable, published with its figures
	 */
	public RedisInstanceMetricsPublisher(ReactiveStringRedisTemplate redisTemplate,
			LocalInstanceMetricsSource localSource, RedisMetricsProperties properties, ObjectMapper objectMapper,
			InstanceIdentity identity, InstanceUri instanceUri) {
		this.redisTemplate = redisTemplate;
		this.localSource = localSource;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.instanceUri = instanceUri;
		this.key = properties.getInstanceKeyPrefix() + identity.id();
	}

	@Override
	public void start() {
		this.subscription = Flux.interval(this.properties.getPublishInterval())
			.concatMap((tick) -> publish())
			.subscribe();
	}

	@Override
	public void stop() {
		Disposable current = this.subscription;
		if (current != null) {
			current.dispose();
		}
	}

	@Override
	public boolean isRunning() {
		Disposable current = this.subscription;
		return current != null && !current.isDisposed();
	}

	/**
	 * Writes the figures of this instance under its own key.
	 * <p>
	 * A failure is logged and swallowed: publishing metrics must never take the gateway
	 * down, and the next tick will try again.
	 * @return a mono completing once the key is written
	 */
	public Mono<Void> publish() {
		return Mono.fromCallable(() -> this.objectMapper.writeValueAsString(reachableAt(this.localSource.read())))
			.flatMap((payload) -> this.redisTemplate.opsForValue()
				.set(this.key, payload, this.properties.getTimeToLive()))
			.onErrorResume((ex) -> {
				LOG.warn("Could not publish the instance metrics of this instance to Redis: {}", ex.getMessage());
				return Mono.empty();
			})
			.then();
	}

	/**
	 * Puts the address of this instance on the figures it is about to publish.
	 * <p>
	 * The local source leaves it out on purpose &mdash; an instance does not know where
	 * it is reached from — and the discovery provider fills it from the registry. Redis
	 * is not a registry, so it is filled here, from the port the server bound or from
	 * what the operator declared. Left empty, the console can still show this instance
	 * among the figures but cannot read its endpoints.
	 */
	private InstanceMetric reachableAt(InstanceMetric metric) {
		String uri = this.instanceUri.get();
		if (!StringUtils.hasText(uri)) {
			return metric;
		}
		return new InstanceMetric(metric.instanceId(), uri, metric.uptimeSeconds(), metric.jvm(), metric.system(),
				metric.netty(), metric.pools(), metric.instrumentation());
	}

	/**
	 * @return the key this instance publishes under
	 */
	public String key() {
		return this.key;
	}

}
