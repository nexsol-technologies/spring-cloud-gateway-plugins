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

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetric.InstanceInstrumentation;
import ch.nexsol.gateway.metrics.InstanceMetric.JvmStats;
import ch.nexsol.gateway.metrics.InstanceMetric.NettyStats;
import ch.nexsol.gateway.metrics.InstanceMetric.SystemStats;
import ch.nexsol.gateway.metrics.LocalInstanceMetricsSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that the technical figures of the instances are found through an index of their
 * own, apart from the one of the route figures: the two kinds of keys live under two
 * prefixes, and each prefix holds its own index.
 */
class RedisInstanceMetricsIndexTests {

	private static final String PREFIX = "gateway:instances:";

	private static final String INDEX = PREFIX + "@index";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FakeRedis redis = new FakeRedis();

	private final RedisMetricsProperties properties = new RedisMetricsProperties();

	@Test
	void readsTheInstancesThroughTheirIndexWithoutWalkingTheKeyspace() throws JsonProcessingException {
		this.redis.strings.put(PREFIX + "pod-a", this.objectMapper.writeValueAsString(metric("pod-a")));
		this.redis.strings.put(PREFIX + "pod-b", this.objectMapper.writeValueAsString(metric("pod-b")));
		this.redis.registered(INDEX, PREFIX + "pod-a", System.currentTimeMillis());
		this.redis.registered(INDEX, PREFIX + "pod-b", System.currentTimeMillis());

		assertThat(source().collect().block().coverage()).isEqualTo("2 instances, via Redis");
		assertThat(this.redis.commands).containsExactly("ZRANGEBYSCORE", "MGET");
	}

	@Test
	void doesNotSeeAnInstanceThatDoesNotRegister() throws JsonProcessingException {
		this.redis.strings.put(PREFIX + "pod-unregistered",
				this.objectMapper.writeValueAsString(metric("pod-unregistered")));

		assertThat(source().collect().block().coverage()).startsWith("no instance published");
		assertThat(this.redis.count("SCAN")).isZero();
	}

	@Test
	void saysRedisIsUnavailableWhenTheIndexCannotBeRead() {
		this.redis.indexDenied = true;

		assertThat(source().collect().block().coverage()).isEqualTo("Redis unavailable");
	}

	@Test
	void registersTheKeyItPublishesInTheIndexOfTheInstances() {
		publisher("pod-a").publish().block();

		assertThat(this.redis.sortedSets).containsOnlyKeys(INDEX);
		assertThat(this.redis.sortedSets.get(INDEX)).containsOnlyKeys(PREFIX + "pod-a");
		assertThat(this.redis.commands).containsExactly("SET", "ZADD", "ZREMRANGEBYSCORE", "EXPIRE");
	}

	@Test
	void keepsTheIndexUnderTheInstanceKeyPrefixItIsConfiguredWith() {
		this.properties.setInstanceKeyPrefix("acme:gw:instances:");

		publisher("pod-a").publish().block();

		assertThat(this.redis.sortedSets).containsOnlyKeys("acme:gw:instances:@index");
		assertThat(source().collect().block().coverage()).isEqualTo("1 instance, via Redis");
	}

	private RedisInstanceMetricsPublisher publisher(String id) {
		LocalInstanceMetricsSource local = mock(LocalInstanceMetricsSource.class);
		when(local.read()).thenReturn(metric(id));
		return new RedisInstanceMetricsPublisher(this.redis.template, local, this.properties, this.objectMapper,
				new InstanceIdentity(id),
				new InstanceUri(this.properties.getInstanceUri(), this.properties.getInstanceScheme(), null));
	}

	private RedisInstanceMetricsSource source() {
		return new RedisInstanceMetricsSource(this.redis.template, this.properties, this.objectMapper);
	}

	private static InstanceMetric metric(String id) {
		return new InstanceMetric(id, null, 180, new JvmStats(1000, 4000, 50, 0.01, 12.0, 3, 40, 55, 20),
				new SystemStats(0.2, 0.4, 1.5, 8, 100, 1024), new NettyStats(0, 8), List.of(),
				new InstanceInstrumentation(true, true));
	}

}
