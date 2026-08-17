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

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.servicegraph.LocalServiceGraphSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Publishes the edges this instance counted into Redis, so the other instances can read
 * them.
 * <p>
 * Each instance writes its own key and never touches the others', which is what keeps
 * concurrent writers from stepping on each other without any locking. The key carries a
 * time to live: an instance that stops publishing fades out of the graph on its own,
 * which is how a replaced pod stops being drawn.
 */
public class RedisServiceGraphPublisher implements SmartLifecycle {

	private static final Logger LOG = LoggerFactory.getLogger(RedisServiceGraphPublisher.class);

	private final ReactiveStringRedisTemplate redisTemplate;

	private final LocalServiceGraphSource localSource;

	private final RedisServiceGraphProperties properties;

	private final ObjectMapper objectMapper;

	private final String key;

	private volatile Disposable subscription;

	/**
	 * Creates the publisher.
	 * @param redisTemplate the reactive Redis template
	 * @param localSource the source reading this instance's counters
	 * @param properties the Redis configuration
	 * @param objectMapper the mapper rendering the edges
	 * @param identity the identity of the running instance
	 */
	public RedisServiceGraphPublisher(ReactiveStringRedisTemplate redisTemplate, LocalServiceGraphSource localSource,
			RedisServiceGraphProperties properties, ObjectMapper objectMapper, InstanceIdentity identity) {
		this.redisTemplate = redisTemplate;
		this.localSource = localSource;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.key = properties.getKeyPrefix() + identity.id();
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
	 * Writes the edges of this instance under its own key.
	 * <p>
	 * A failure is logged and swallowed: publishing a graph must never take the gateway
	 * down, and the next tick will try again.
	 * @return a mono completing once the key is written
	 */
	public Mono<Void> publish() {
		return Mono.fromCallable(() -> this.objectMapper.writeValueAsString(this.localSource.read()))
			.flatMap((payload) -> this.redisTemplate.opsForValue()
				.set(this.key, payload, this.properties.getTimeToLive()))
			.onErrorResume((ex) -> {
				LOG.warn("Could not publish the service graph of this instance to Redis: {}", ex.getMessage());
				return Mono.empty();
			})
			.then();
	}

	/**
	 * @return the key this instance publishes under
	 */
	public String key() {
		return this.key;
	}

}
