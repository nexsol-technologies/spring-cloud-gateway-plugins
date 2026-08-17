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

import java.time.Duration;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.servicegraph.LocalServiceGraphSource;
import ch.nexsol.gateway.servicegraph.ServiceGraphFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RedisServiceGraphPublisher}.
 */
class RedisServiceGraphPublisherTests {

	private SimpleMeterRegistry registry;

	private ReactiveStringRedisTemplate redisTemplate;

	private ReactiveValueOperations<String, String> valueOperations;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.registry = new SimpleMeterRegistry();
		this.redisTemplate = mock(ReactiveStringRedisTemplate.class);
		this.valueOperations = mock(ReactiveValueOperations.class);
		when(this.redisTemplate.opsForValue()).thenReturn(this.valueOperations);
		when(this.valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
	}

	@Test
	void writesTheEdgesOfThisInstanceUnderItsOwnKey() {
		count("web", "orders", "orders-route", ServiceGraphFilter.SUCCESS);
		RedisServiceGraphPublisher publisher = publisher(new RedisServiceGraphProperties());

		publisher.publish().block();

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(this.valueOperations).set(eq("gateway:service-graph:pod-a"), payload.capture(), any(Duration.class));
		assertThat(payload.getValue()).contains("\"from\":\"web\"")
			.contains("\"to\":\"orders\"")
			.contains("\"routeId\":\"orders-route\"");
	}

	@Test
	void writesTheKeyWithTheConfiguredTimeToLive() {
		RedisServiceGraphProperties properties = new RedisServiceGraphProperties();
		properties.setTimeToLive(Duration.ofSeconds(90));

		publisher(properties).publish().block();

		verify(this.valueOperations).set(anyString(), anyString(), eq(Duration.ofSeconds(90)));
	}

	@Test
	void usesTheConfiguredKeyPrefix() {
		RedisServiceGraphProperties properties = new RedisServiceGraphProperties();
		properties.setKeyPrefix("graph:");

		assertThat(publisher(properties).key()).isEqualTo("graph:pod-a");
	}

	@Test
	void swallowsAFailureSoTheGatewayKeepsServing() {
		when(this.valueOperations.set(anyString(), anyString(), any(Duration.class)))
			.thenReturn(Mono.error(new IllegalStateException("redis is down")));

		assertThat(publisher(new RedisServiceGraphProperties()).publish().block()).isNull();
	}

	private void count(String caller, String service, String route, String outcome) {
		this.registry
			.counter(ServiceGraphFilter.CALLS_METER, ServiceGraphFilter.CALLER_TAG, caller,
					ServiceGraphFilter.SERVICE_TAG, service, ServiceGraphFilter.ROUTE_TAG, route,
					ServiceGraphFilter.OUTCOME_TAG, outcome)
			.increment();
	}

	private RedisServiceGraphPublisher publisher(RedisServiceGraphProperties properties) {
		@SuppressWarnings("unchecked")
		ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.registry);
		LocalServiceGraphSource localSource = new LocalServiceGraphSource(provider, new InstanceIdentity("pod-a"));
		return new RedisServiceGraphPublisher(this.redisTemplate, localSource, properties, new ObjectMapper(),
				new InstanceIdentity("pod-a"));
	}

}
