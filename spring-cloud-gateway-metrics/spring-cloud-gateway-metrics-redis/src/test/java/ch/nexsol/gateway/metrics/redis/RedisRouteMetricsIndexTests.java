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
import ch.nexsol.gateway.metrics.LocalRouteMetricsSource;
import ch.nexsol.gateway.metrics.MetricsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how the fleet is found in a Redis this plugin shares with other uses: through the
 * index the instances register in, kept under their own key prefix, never by walking
 * every key Redis holds.
 */
class RedisRouteMetricsIndexTests {

	private static final String PREFIX = "gateway:metrics:";

	private static final String INDEX = PREFIX + "@index";

	private static final String PAYLOAD = "[]";

	private final FakeRedis redis = new FakeRedis();

	private final RedisMetricsProperties properties = new RedisMetricsProperties();

	private RedisRouteMetricsSource source;

	@Test
	void readsTheFleetThroughTheIndexWithoutWalkingTheKeyspace() {
		for (int i = 0; i < 200; i++) {
			this.redis.strings.put("spring:session:" + i, "x");
		}
		instance("pod-a", true);
		instance("pod-b", true);
		instance("pod-c", true);

		assertThat(coverage()).isEqualTo("3 instances, via Redis");
		assertThat(this.redis.commands).containsExactly("ZRANGEBYSCORE", "MGET");
	}

	@Test
	void neverWalksTheKeyspaceHoweverOftenItReads() {
		instance("pod-a", true);

		for (int i = 0; i < 4; i++) {
			coverage();
		}

		assertThat(this.redis.count("SCAN")).isZero();
		assertThat(this.redis.count("ZRANGEBYSCORE")).isEqualTo(4);
	}

	@Test
	void doesNotSeeAnInstanceThatDoesNotRegister() {
		instance("pod-registered", true);
		instance("pod-unregistered", false);

		assertThat(coverage()).isEqualTo("1 instance, via Redis");
	}

	@Test
	void doesNotReadAnEntryOfTheIndexThatExpired() {
		this.redis.strings.put(PREFIX + "pod-gone", PAYLOAD);
		this.redis.registered(INDEX, PREFIX + "pod-gone",
				System.currentTimeMillis() - this.properties.getTimeToLive().plusSeconds(30).toMillis());

		assertThat(coverage()).startsWith("no instance published");
		assertThat(this.redis.count("MGET")).isZero();
	}

	@Test
	void leavesOutAnInstanceWhoseKeyExpiredSinceItRegistered() {
		instance("pod-a", true);
		this.redis.registered(INDEX, PREFIX + "pod-gone", System.currentTimeMillis());

		assertThat(coverage()).isEqualTo("1 instance, via Redis");
	}

	@Test
	void saysRedisIsUnavailableWhenTheIndexCannotBeRead() {
		this.redis.indexDenied = true;
		instance("pod-a", true);

		assertThat(coverage()).isEqualTo("Redis unavailable");
		assertThat(this.redis.count("SCAN")).isZero();
	}

	@Test
	void registersTheKeyItPublishes() {
		publish("pod-a");

		assertThat(this.redis.strings).containsKey(PREFIX + "pod-a");
		assertThat(this.redis.sortedSets.get(INDEX)).containsOnlyKeys(PREFIX + "pod-a");
		assertThat(this.redis.commands).containsExactly("SET", "ZADD", "ZREMRANGEBYSCORE", "EXPIRE");
	}

	@Test
	void dropsFromTheIndexTheKeysThatExpired() {
		this.redis.registered(INDEX, PREFIX + "pod-gone",
				System.currentTimeMillis() - this.properties.getTimeToLive().plusSeconds(30).toMillis());

		publish("pod-a");

		assertThat(this.redis.sortedSets.get(INDEX)).containsOnlyKeys(PREFIX + "pod-a");
	}

	@Test
	void stillPublishesWhenTheIndexCannotBeWritten() {
		this.redis.indexDenied = true;

		publish("pod-a");

		assertThat(this.redis.strings).containsKey(PREFIX + "pod-a");
	}

	@Test
	void keepsTheIndexUnderTheKeyPrefixItIsConfiguredWith() {
		this.properties.setKeyPrefix("acme:gw:metrics:");

		publish("pod-a");

		assertThat(this.redis.sortedSets).containsOnlyKeys("acme:gw:metrics:@index");
		assertThat(this.redis.strings.keySet()).allMatch((key) -> key.startsWith("acme:gw:metrics:"));
		assertThat(coverage()).isEqualTo("1 instance, via Redis");
	}

	@Test
	void findsWhatItPublished() {
		publish("pod-a");
		publish("pod-b");

		assertThat(coverage()).isEqualTo("2 instances, via Redis");
	}

	private void instance(String id, boolean registers) {
		this.redis.strings.put(PREFIX + id, PAYLOAD);
		if (registers) {
			this.redis.registered(INDEX, PREFIX + id, System.currentTimeMillis());
		}
	}

	private String coverage() {
		// One source for the whole test, as a gateway has.
		if (this.source == null) {
			this.source = new RedisRouteMetricsSource(this.redis.template, this.properties, new ObjectMapper());
		}
		return this.source.collect().block().coverage();
	}

	private void publish(String id) {
		InstanceIdentity identity = new InstanceIdentity(id);
		LocalRouteMetricsSource local = new LocalRouteMetricsSource(new NoMeterRegistry(), new MetricsProperties(),
				identity);
		new RedisRouteMetricsPublisher(this.redis.template, local, this.properties, new ObjectMapper(), identity)
			.publish()
			.block();
	}

}
