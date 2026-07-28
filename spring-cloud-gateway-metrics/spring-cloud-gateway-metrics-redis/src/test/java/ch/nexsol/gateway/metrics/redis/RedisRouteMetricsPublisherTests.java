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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ch.nexsol.gateway.metrics.InstanceIdentity;
import ch.nexsol.gateway.metrics.LocalRouteMetricsSource;
import ch.nexsol.gateway.metrics.MetricsProperties;
import ch.nexsol.gateway.metrics.RouteMetric;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the task publishing this instance's figures into Redis.
 */
class RedisRouteMetricsPublisherTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private ReactiveStringRedisTemplate redisTemplate;

	private ReactiveValueOperations<String, String> valueOperations;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.redisTemplate = mock(ReactiveStringRedisTemplate.class);
		this.valueOperations = mock(ReactiveValueOperations.class);
		when(this.redisTemplate.opsForValue()).thenReturn(this.valueOperations);
		when(this.valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
	}

	@SuppressWarnings("unchecked")
	private LocalRouteMetricsSource localSource(MeterRegistry registry) {
		ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(registry);
		return new LocalRouteMetricsSource(provider, new MetricsProperties(), new InstanceIdentity("pod-a"));
	}

	private RedisRouteMetricsPublisher publisher(MeterRegistry registry, RedisMetricsProperties properties) {
		return new RedisRouteMetricsPublisher(this.redisTemplate, localSource(registry), properties, this.objectMapper,
				new InstanceIdentity("pod-a"));
	}

	private static SimpleMeterRegistry registryWith(String routeId, int calls) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Timer timer = Timer.builder(LocalRouteMetricsSource.REQUESTS_METER)
			.tags("routeId", routeId, "routeUri", "http://" + routeId, "httpStatusCode", "200")
			.register(registry);
		for (int i = 0; i < calls; i++) {
			timer.record(10, TimeUnit.MILLISECONDS);
		}
		return registry;
	}

	@Test
	void writesTheFiguresUnderTheKeyOfThisInstance() {
		publisher(registryWith("orders", 3), new RedisMetricsProperties()).publish().block();

		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		verify(this.valueOperations).set(key.capture(), anyString(), any(Duration.class));
		// One key per instance is what lets every instance write without any locking.
		assertThat(key.getValue()).isEqualTo("gateway:metrics:pod-a");
	}

	@Test
	void writesWhatThisInstanceCounted() throws Exception {
		publisher(registryWith("orders", 3), new RedisMetricsProperties()).publish().block();

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(this.valueOperations).set(anyString(), payload.capture(), any(Duration.class));
		List<RouteMetric> published = this.objectMapper.readValue(payload.getValue(),
				this.objectMapper.getTypeFactory().constructCollectionType(List.class, RouteMetric.class));
		assertThat(published).singleElement().satisfies((metric) -> {
			assertThat(metric.routeId()).isEqualTo("orders");
			assertThat(metric.count()).isEqualTo(3);
		});
	}

	@Test
	void appliesTheConfiguredTimeToLiveSoAStoppedInstanceFadesOut() {
		RedisMetricsProperties properties = new RedisMetricsProperties();
		properties.setTimeToLive(Duration.ofSeconds(90));

		publisher(registryWith("orders", 1), properties).publish().block();

		ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
		verify(this.valueOperations).set(anyString(), anyString(), ttl.capture());
		assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(90));
	}

	@Test
	void honoursTheConfiguredKeyPrefix() {
		RedisMetricsProperties properties = new RedisMetricsProperties();
		properties.setKeyPrefix("custom:prefix:");

		assertThat(publisher(registryWith("orders", 1), properties).key()).isEqualTo("custom:prefix:pod-a");
	}

	@Test
	void swallowsAFailureSoTheGatewayKeepsServing() {
		when(this.valueOperations.set(anyString(), anyString(), any(Duration.class)))
			.thenReturn(Mono.error(new IllegalStateException("connection refused")));

		// Publishing metrics must never take the gateway down; the next tick retries.
		assertThat(publisher(registryWith("orders", 1), new RedisMetricsProperties()).publish().block()).isNull();
	}

	@Test
	void publishesAnEmptyListWhenNoTrafficWasSeen() {
		publisher(new SimpleMeterRegistry(), new RedisMetricsProperties()).publish().block();

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(this.valueOperations).set(anyString(), payload.capture(), any(Duration.class));
		assertThat(payload.getValue()).isEqualTo("[]");
	}

}
