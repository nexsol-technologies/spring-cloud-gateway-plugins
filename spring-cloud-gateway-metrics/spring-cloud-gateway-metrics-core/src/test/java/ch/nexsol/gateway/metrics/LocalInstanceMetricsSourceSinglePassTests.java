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

package ch.nexsol.gateway.metrics;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ch.nexsol.gateway.commons.InstanceIdentity;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.config.HttpClientProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that one reading of the figures of this instance walks the registry once. Each
 * search copies and filters every meter of the registry, and a gateway carries one timer
 * per route, status and outcome: some thirty searches per reading is thirty walks over
 * tens of thousands of meters, on every publication and on every poll of the console.
 */
class LocalInstanceMetricsSourceSinglePassTests {

	private MeterRegistry registry;

	private LocalInstanceMetricsSource source;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.registry = spy(new SimpleMeterRegistry());
		ObjectProvider<MeterRegistry> registryProvider = mock(ObjectProvider.class);
		when(registryProvider.getIfAvailable()).thenReturn(this.registry);
		ObjectProvider<HttpClientProperties> httpClientProvider = mock(ObjectProvider.class);
		when(httpClientProvider.getIfAvailable()).thenReturn(new HttpClientProperties());
		this.source = new LocalInstanceMetricsSource(registryProvider, httpClientProvider, absent(), absent(),
				new MetricsProperties(), new InstanceIdentity("gateway-1"));
	}

	@Test
	void walksTheRegistryOnceForAWholeReading() {
		gauge("jvm.threads.live", 42);

		this.source.read();

		verify(this.registry, times(1)).getMeters();
		verify(this.registry, never()).find(anyString());
	}

	@Test
	void stillReadsWhatTheSearchesRead() {
		gauge("jvm.threads.live", 42);
		gauge("jvm.threads.peak", 55);
		gauge("system.cpu.usage", 0.25);
		gauge("process.files.open", 120);
		Gauge.builder(LocalInstanceMetricsSource.EVENT_LOOP_PENDING_TASKS, new AtomicInteger(3), AtomicInteger::get)
			.tag("name", "loop-1")
			.register(this.registry);
		Gauge.builder(LocalInstanceMetricsSource.EVENT_LOOP_PENDING_TASKS, new AtomicInteger(4), AtomicInteger::get)
			.tag("name", "loop-2")
			.register(this.registry);
		pool("active.connections", "orders", 7);
		pool("max.connections", "orders", 50);
		Timer.builder(LocalInstanceMetricsSource.POOL_PREFIX + "pending.connections.time")
			.tag(LocalInstanceMetricsSource.POOL_NAME_TAG, "orders")
			.tag(LocalInstanceMetricsSource.REMOTE_ADDRESS_TAG, "orders:8080")
			.register(this.registry)
			.record(30, TimeUnit.MILLISECONDS);
		Timer.builder("jvm.gc.pause").tag("cause", "young").register(this.registry).record(12, TimeUnit.MILLISECONDS);
		// A meter the reading has no use for, of which a gateway holds thousands.
		Timer.builder("spring.cloud.gateway.requests").tag("routeId", "orders").register(this.registry);

		InstanceMetric metric = this.source.read();

		assertThat(metric.jvm().threadsLive()).isEqualTo(42);
		assertThat(metric.jvm().threadsPeak()).isEqualTo(55);
		assertThat(metric.jvm().gcPauseCount()).isEqualTo(1);
		assertThat(metric.jvm().gcPauseTotalMs()).isEqualTo(12.0);
		assertThat(metric.system().systemCpuUsage()).isEqualTo(0.25);
		assertThat(metric.system().openFiles()).isEqualTo(120);
		assertThat(metric.netty().eventLoops()).isEqualTo(2);
		assertThat(metric.netty().eventLoopPendingTasks()).isEqualTo(7);
		assertThat(metric.pools()).singleElement().satisfies((pool) -> {
			assertThat(pool.name()).isEqualTo("orders");
			assertThat(pool.remoteAddress()).isEqualTo("orders:8080");
			assertThat(pool.active()).isEqualTo(7.0);
			assertThat(pool.max()).isEqualTo(50.0);
			assertThat(pool.pendingTimeAvgMs()).isEqualTo(30.0);
		});
	}

	@Test
	void reportsWhatIsMissingAsMissing() {
		InstanceMetric metric = this.source.read();

		assertThat(metric.jvm().heapUsedBytes()).isEqualTo(-1);
		assertThat(metric.jvm().threadsLive()).isZero();
		assertThat(metric.system().openFiles()).isEqualTo(-1);
		assertThat(metric.pools()).isEmpty();
	}

	private void gauge(String name, double value) {
		Gauge.builder(name, () -> value).register(this.registry);
	}

	private void pool(String suffix, String name, double value) {
		Gauge.builder(LocalInstanceMetricsSource.POOL_PREFIX + suffix, () -> value)
			.tag(LocalInstanceMetricsSource.POOL_NAME_TAG, name)
			.tag(LocalInstanceMetricsSource.REMOTE_ADDRESS_TAG, name + ":8080")
			.register(this.registry);
	}

	/** A provider of a bean the application does not declare. */
	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> absent() {
		return mock(ObjectProvider.class);
	}

}
