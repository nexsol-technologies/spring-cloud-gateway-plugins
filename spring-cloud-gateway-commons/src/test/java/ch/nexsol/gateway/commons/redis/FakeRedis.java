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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import org.springframework.data.redis.core.ScanOptions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Redis small enough to reason about: strings, sorted sets, and the list of the
 * commands it was sent. It answers the way Redis does where these tests depend on it
 * &mdash; reading several keys at once answers nothing for a key that is missing or that
 * holds a sorted set, while reading a sorted set alone as a string fails.
 */
final class FakeRedis {

	final Map<String, String> strings = new ConcurrentHashMap<>();

	final Map<String, Map<String, Double>> sortedSets = new ConcurrentHashMap<>();

	final List<String> commands = new CopyOnWriteArrayList<>();

	final ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);

	volatile boolean indexDenied;

	@SuppressWarnings("unchecked")
	FakeRedis() {
		ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
		ReactiveZSetOperations<String, String> sets = mock(ReactiveZSetOperations.class);
		when(this.template.opsForValue()).thenReturn(values);
		when(this.template.opsForZSet()).thenReturn(sets);
		when(values.set(anyString(), anyString(), any(Duration.class))).thenAnswer((call) -> Mono.fromSupplier(() -> {
			this.commands.add("SET");
			this.strings.put(call.getArgument(0), call.getArgument(1));
			return true;
		}));
		when(values.get(any())).thenAnswer((call) -> Mono.defer(() -> {
			this.commands.add("GET");
			String key = call.getArgument(0);
			if (this.sortedSets.containsKey(key)) {
				return Mono
					.error(new IllegalStateException("WRONGTYPE Operation against a key holding the wrong kind"));
			}
			return Mono.justOrEmpty(this.strings.get(key));
		}));
		when(values.multiGet(any())).thenAnswer((call) -> Mono.fromSupplier(() -> {
			this.commands.add("MGET");
			Collection<String> keys = call.getArgument(0);
			return keys.stream().map(this.strings::get).collect(Collectors.toCollection(ArrayList::new));
		}));
		when(sets.add(anyString(), anyString(), anyDouble())).thenAnswer((call) -> onTheIndex("ZADD", () -> {
			members(call.getArgument(0)).put(call.getArgument(1), call.getArgument(2));
			return true;
		}));
		when(sets.rangeByScore(anyString(), any(Range.class))).thenAnswer((call) -> onTheIndex("ZRANGEBYSCORE", () -> {
			Range<Double> range = call.getArgument(1);
			double from = range.getLowerBound().getValue().orElse(Double.NEGATIVE_INFINITY);
			Map<String, Double> members = this.sortedSets.getOrDefault(call.getArgument(0), Map.of());
			return members.entrySet()
				.stream()
				.filter((member) -> member.getValue() >= from)
				.sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
				.map(Map.Entry::getKey)
				.toList();
		}).flatMapMany(Flux::fromIterable));
		when(sets.removeRangeByScore(anyString(), any(Range.class)))
			.thenAnswer((call) -> onTheIndex("ZREMRANGEBYSCORE", () -> {
				Range<Double> range = call.getArgument(1);
				double until = range.getUpperBound().getValue().orElse(Double.POSITIVE_INFINITY);
				Map<String, Double> members = members(call.getArgument(0));
				long before = members.size();
				members.values().removeIf((score) -> score < until);
				return before - members.size();
			}));
		when(this.template.scan(any(ScanOptions.class))).thenAnswer((call) -> Flux.defer(() -> {
			this.commands.add("SCAN");
			ScanOptions options = call.getArgument(0);
			String prefix = options.getPattern().replace("*", "");
			return Flux.fromStream(Stream.concat(this.strings.keySet().stream(), this.sortedSets.keySet().stream())
				.filter((key) -> key.startsWith(prefix))
				.sorted());
		}));
		when(this.template.expire(anyString(), any(Duration.class))).thenAnswer((call) -> Mono.fromSupplier(() -> {
			this.commands.add("EXPIRE");
			String key = call.getArgument(0);
			return this.strings.containsKey(key) || this.sortedSets.containsKey(key);
		}));
		when(this.template.delete(any(String[].class))).thenAnswer((call) -> Mono.fromSupplier(() -> {
			this.commands.add("DEL");
			long deleted = 0;
			for (Object key : call.getArguments()) {
				deleted += (this.strings.remove(key) != null) ? 1 : 0;
				deleted += (this.sortedSets.remove(key) != null) ? 1 : 0;
			}
			return deleted;
		}));
	}

	/** Registers a key in an index the way an instance publishing it does. */
	void registered(String index, String key, double score) {
		members(index).put(key, score);
	}

	long count(String command) {
		return this.commands.stream().filter(command::equals).count();
	}

	private Map<String, Double> members(String index) {
		return this.sortedSets.computeIfAbsent(index, (key) -> new ConcurrentHashMap<>());
	}

	private <T> Mono<T> onTheIndex(String command, Supplier<T> answer) {
		return Mono.defer(() -> {
			this.commands.add(command);
			if (this.indexDenied) {
				return Mono.error(new IllegalStateException("NOPERM this user has no permissions to access the key"));
			}
			return Mono.just(answer.get());
		});
	}

}
