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

package ch.nexsol.gateway.commons.redis;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;

/**
 * The index of the keys the instances publish under: a sorted set holding each key
 * against the time it was last published.
 * <p>
 * Without it the keys of the fleet can only be found with a {@code SCAN}, and a scan
 * walks every key Redis holds whatever pattern it is given &mdash; the sessions, the rate
 * limits and the tokens of a Redis this plugin shares with them included. Reading the
 * index costs one command, whatever else Redis holds.
 * <p>
 * The index lives under the prefix of the keys it lists, so the access rule an operator
 * wrote for those keys covers it too. It is the one key under that prefix that is not a
 * string: whatever reads the prefix has to go through {@link #keys()} or
 * {@link #values()}, never read every key found there.
 */
public final class InstanceIndex {

	private static final Logger LOG = LoggerFactory.getLogger(InstanceIndex.class);

	private static final String NAME = "@index";

	private final ReactiveStringRedisTemplate redisTemplate;

	private final String key;

	private final Duration timeToLive;

	private final Clock clock;

	private final AtomicBoolean unwritable = new AtomicBoolean();

	/**
	 * Creates the index of the keys published under one prefix.
	 * @param redisTemplate the reactive Redis template
	 * @param keyPrefix the prefix the instances publish their keys under
	 * @param timeToLive how long a published key survives, hence how long its entry does
	 */
	public InstanceIndex(ReactiveStringRedisTemplate redisTemplate, String keyPrefix, Duration timeToLive) {
		this(redisTemplate, keyPrefix, timeToLive, Clock.systemUTC());
	}

	InstanceIndex(ReactiveStringRedisTemplate redisTemplate, String keyPrefix, Duration timeToLive, Clock clock) {
		this.redisTemplate = redisTemplate;
		this.key = keyPrefix + NAME;
		this.timeToLive = timeToLive;
		this.clock = clock;
	}

	/**
	 * The key the index is held under, for whoever has to delete it.
	 * @return the key of the index
	 */
	public String key() {
		return this.key;
	}

	/**
	 * Registers a key just published, drops the entries of the keys that expired, and
	 * keeps the index itself from outliving the last instance.
	 * <p>
	 * Never fails: an index that cannot be written must not cost this instance its
	 * publication.
	 * @param published the key just published
	 * @return a mono completing once the index is up to date
	 */
	public Mono<Void> register(String published) {
		return Mono.defer(() -> {
			double now = this.clock.millis();
			ReactiveZSetOperations<String, String> index = this.redisTemplate.opsForZSet();
			return index.add(this.key, published, now)
				.then(index.removeRangeByScore(this.key, Range.leftUnbounded(Range.Bound.exclusive(expiry(now)))))
				.then(this.redisTemplate.expire(this.key, this.timeToLive))
				.doOnNext((kept) -> this.unwritable.set(false))
				.then();
		}).onErrorResume((ex) -> {
			if (this.unwritable.compareAndSet(false, true)) {
				LOG.warn("Could not register {} in the index {}: {}. The instances reading the fleet will not see "
						+ "this one.", published, this.key, ex.toString());
			}
			return Mono.empty();
		});
	}

	/**
	 * Lists the keys of the instances that published lately.
	 * @return the keys registered within the time a published key survives
	 */
	public Mono<List<String>> keys() {
		return Mono.defer(() -> {
			double now = this.clock.millis();
			return this.redisTemplate.opsForZSet()
				.rangeByScore(this.key, Range.rightUnbounded(Range.Bound.inclusive(expiry(now))))
				.collectList();
		});
	}

	/**
	 * Reads what the instances published: the value of every key {@link #keys()} lists,
	 * in one command whatever their number.
	 * <p>
	 * A key that expired between the listing and the read has no value: {@code MGET}
	 * answers nothing for it instead of failing, and it is left out.
	 * @return the values published, without the missing ones
	 */
	public Mono<List<String>> values() {
		// Redis refuses an MGET naming no key.
		return keys().flatMap((keys) -> keys.isEmpty() ? Mono.just(List.<String>of())
				: this.redisTemplate.opsForValue()
					.multiGet(keys)
					.map((values) -> values.stream().filter(Objects::nonNull).toList()));
	}

	private double expiry(double now) {
		return now - this.timeToLive.toMillis();
	}

}
