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
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link InstanceIndex}: where the index lives, what registering and reading it
 * cost, and what becomes of an instance that stops publishing.
 */
class InstanceIndexTests {

	private static final String PREFIX = "gateway:things:";

	private static final String INDEX = "gateway:things:@index";

	private static final Duration TIME_TO_LIVE = Duration.ofSeconds(45);

	private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

	private final FakeRedis redis = new FakeRedis();

	@ParameterizedTest
	@ValueSource(strings = { "gateway:things:", "gateway:things", "acme:gw:things:", "things-", "a:b:c:d:" })
	void theIndexLivesUnderThePrefixOfTheKeysItLists(String prefix) {
		InstanceIndex index = new InstanceIndex(this.redis.template, prefix, TIME_TO_LIVE);

		assertThat(index.key()).startsWith(prefix).isEqualTo(prefix + "@index");
	}

	@Test
	void registeringCostsThreeCommandsWhateverRedisHolds() {
		for (int i = 0; i < 500; i++) {
			this.redis.strings.put("session:" + i, "x");
		}

		index().register(PREFIX + "pod-a").block();

		assertThat(this.redis.commands).containsExactly("ZADD", "ZREMRANGEBYSCORE", "EXPIRE");
		assertThat(this.redis.sortedSets.get(INDEX)).containsEntry(PREFIX + "pod-a", (double) NOW.toEpochMilli());
	}

	@Test
	void registeringDropsTheEntriesOfTheKeysThatExpired() {
		this.redis.registered(INDEX, PREFIX + "gone", NOW.minus(TIME_TO_LIVE).minusSeconds(1).toEpochMilli());
		this.redis.registered(INDEX, PREFIX + "alive", NOW.minusSeconds(10).toEpochMilli());

		index().register(PREFIX + "pod-a").block();

		assertThat(this.redis.sortedSets.get(INDEX)).containsOnlyKeys(PREFIX + "alive", PREFIX + "pod-a");
	}

	@Test
	void registeringNeverFails() {
		this.redis.indexDenied = true;

		index().register(PREFIX + "pod-a").block();

		assertThat(this.redis.sortedSets).isEmpty();
	}

	@Test
	void readingCostsOneCommandWhateverRedisHoldsAndNeverWalksTheKeys() {
		for (int i = 0; i < 500; i++) {
			this.redis.strings.put("session:" + i, "x");
		}
		this.redis.registered(INDEX, PREFIX + "pod-a", NOW.toEpochMilli());

		assertThat(index().keys().block()).containsExactly(PREFIX + "pod-a");
		assertThat(this.redis.commands).containsExactly("ZRANGEBYSCORE");
	}

	@Test
	void anInstanceThatStoppedPublishingIsNoLongerListedOnceItsKeyExpired() {
		this.redis.registered(INDEX, PREFIX + "gone", NOW.minus(TIME_TO_LIVE).minusSeconds(1).toEpochMilli());
		this.redis.registered(INDEX, PREFIX + "alive", NOW.minus(TIME_TO_LIVE).plusSeconds(1).toEpochMilli());

		assertThat(index().keys().block()).containsExactly(PREFIX + "alive");
	}

	@Test
	void anInstanceThatNeverRegisteredIsNotLookedFor() {
		this.redis.strings.put(PREFIX + "unregistered", "x");
		this.redis.registered(INDEX, PREFIX + "pod-a", NOW.toEpochMilli());

		assertThat(index().keys().block()).containsExactly(PREFIX + "pod-a");
		assertThat(this.redis.count("SCAN")).isZero();
	}

	@Test
	void anIndexThatCannotBeReadFailsTheReadRatherThanAnsweringAnEmptyFleet() {
		this.redis.indexDenied = true;

		StepVerifier.create(index().keys())
			.expectErrorMessage("NOPERM this user has no permissions to access the key")
			.verify();
		StepVerifier.create(index().values()).expectError(IllegalStateException.class).verify();
		assertThat(this.redis.count("SCAN")).isZero();
	}

	@Test
	void readsTheValuesOfEveryInstanceInOneCommand() {
		InstanceIndex index = index();
		for (String pod : new String[] { "pod-a", "pod-b", "pod-c" }) {
			this.redis.strings.put(PREFIX + pod, "figures of " + pod);
			index.register(PREFIX + pod).block();
		}
		this.redis.commands.clear();

		assertThat(index.values().block()).containsExactlyInAnyOrder("figures of pod-a", "figures of pod-b",
				"figures of pod-c");
		assertThat(this.redis.commands).containsExactly("ZRANGEBYSCORE", "MGET");
	}

	@Test
	void leavesOutAKeyThatExpiredBetweenTheListingAndTheRead() {
		InstanceIndex index = index();
		this.redis.strings.put(PREFIX + "pod-a", "figures of pod-a");
		index.register(PREFIX + "pod-a").block();
		index.register(PREFIX + "pod-gone").block();

		assertThat(index.values().block()).containsExactly("figures of pod-a");
	}

	@Test
	void sendsNoReadWhenNoInstancePublished() {
		assertThat(index().values().block()).isEmpty();
		assertThat(this.redis.count("MGET")).isZero();
	}

	@Test
	void theIndexItselfIsNeverReadAsOneOfTheValues() {
		InstanceIndex index = index();
		this.redis.strings.put(PREFIX + "pod-a", "figures of pod-a");
		index.register(PREFIX + "pod-a").block();

		assertThat(index.keys().block()).doesNotContain(INDEX);
		assertThat(index.values().block()).containsExactly("figures of pod-a");
	}

	private InstanceIndex index() {
		return new InstanceIndex(this.redis.template, PREFIX, TIME_TO_LIVE, Clock.fixed(NOW, ZoneOffset.UTC));
	}

}
