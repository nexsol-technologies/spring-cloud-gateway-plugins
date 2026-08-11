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
import java.util.concurrent.atomic.AtomicLong;

import ch.nexsol.gateway.metrics.InstanceMetric.PoolStats;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.config.HttpClientProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LocalInstanceMetricsSource}.
 */
class LocalInstanceMetricsSourceTests {

	private LocalInstanceMetricsSource sourceFor(MeterRegistry registry) {
		return sourceFor(registry, new MetricsProperties(), new HttpClientProperties());
	}

	@SuppressWarnings("unchecked")
	private LocalInstanceMetricsSource sourceFor(MeterRegistry registry, MetricsProperties properties,
			HttpClientProperties httpClientProperties) {
		ObjectProvider<MeterRegistry> registryProvider = mock(ObjectProvider.class);
		when(registryProvider.getIfAvailable()).thenReturn(registry);
		ObjectProvider<HttpClientProperties> httpClientProvider = mock(ObjectProvider.class);
		when(httpClientProvider.getIfAvailable()).thenReturn(httpClientProperties);
		return new LocalInstanceMetricsSource(registryProvider, httpClientProvider, properties,
				new InstanceIdentity("gateway-1"));
	}

	private static Gauge gauge(MeterRegistry registry, String name, double value, String... tags) {
		return Gauge.builder(name, () -> value).tags(tags).register(registry);
	}

	@Test
	void namesTheInstanceTheFiguresCameFrom() {
		InstanceMetricsSnapshot snapshot = sourceFor(new SimpleMeterRegistry()).collect().block();

		assertThat(snapshot.coverage()).contains("this instance only").contains("gateway-1");
		assertThat(snapshot.instances()).singleElement().extracting(InstanceMetric::instanceId).isEqualTo("gateway-1");
	}

	@Test
	void readsTheJvmFigures() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		gauge(registry, "jvm.memory.used", 300, "area", "heap", "id", "eden");
		gauge(registry, "jvm.memory.used", 700, "area", "heap", "id", "old");
		gauge(registry, "jvm.memory.used", 50, "area", "nonheap", "id", "metaspace");
		gauge(registry, "jvm.memory.max", 2000, "area", "heap", "id", "old");
		gauge(registry, "jvm.threads.live", 87);
		gauge(registry, "jvm.threads.peak", 112);
		gauge(registry, "jvm.threads.daemon", 40);
		gauge(registry, "jvm.gc.overhead", 0.04);
		Timer.builder("jvm.gc.pause")
			.tag("action", "end of minor GC")
			.register(registry)
			.record(12, TimeUnit.MILLISECONDS);

		InstanceMetric.JvmStats jvm = sourceFor(registry).read().jvm();

		assertThat(jvm.heapUsedBytes()).isEqualTo(1000);
		assertThat(jvm.heapMaxBytes()).isEqualTo(2000);
		assertThat(jvm.nonHeapUsedBytes()).isEqualTo(50);
		assertThat(jvm.threadsLive()).isEqualTo(87);
		assertThat(jvm.threadsPeak()).isEqualTo(112);
		assertThat(jvm.threadsDaemon()).isEqualTo(40);
		assertThat(jvm.gcOverhead()).isEqualTo(0.04, offset(0.0001));
		assertThat(jvm.gcPauseTotalMs()).isEqualTo(12.0, offset(0.001));
		assertThat(jvm.gcPauseCount()).isEqualTo(1);
	}

	@Test
	void reportsAMissingFigureAsMinusOneRatherThanZero() {
		InstanceMetric metric = sourceFor(new SimpleMeterRegistry()).read();

		// A zero would read as "no memory used" and "no file open", which is a different
		// and wrong statement from "this JVM does not publish the figure".
		assertThat(metric.jvm().heapUsedBytes()).isEqualTo(-1);
		assertThat(metric.jvm().heapMaxBytes()).isEqualTo(-1);
		assertThat(metric.system().openFiles()).isEqualTo(-1);
		assertThat(metric.system().maxFiles()).isEqualTo(-1);
		assertThat(metric.system().loadAverage1m()).isEqualTo(-1);
	}

	@Test
	void leavesAnUnboundedMemoryPoolOutOfTheHeapCeiling() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		gauge(registry, "jvm.memory.max", 2000, "area", "heap", "id", "old");
		gauge(registry, "jvm.memory.max", -1, "area", "heap", "id", "eden");

		// Adding the -1 of the unbounded pool would silently shrink the ceiling.
		assertThat(sourceFor(registry).read().jvm().heapMaxBytes()).isEqualTo(2000);
	}

	@Test
	void readsTheMemoryAndProcessorFiguresUnderTheOpenTelemetryConventions() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		gauge(registry, "jvm.memory.used", 300, "jvm.memory.type", "heap", "jvm.memory.pool.name", "G1 Eden Space");
		gauge(registry, "jvm.memory.used", 700, "jvm.memory.type", "heap", "jvm.memory.pool.name", "G1 Old Gen");
		gauge(registry, "jvm.memory.used", 50, "jvm.memory.type", "non_heap", "jvm.memory.pool.name", "Metaspace");
		gauge(registry, "jvm.memory.limit", 2000, "jvm.memory.type", "heap", "jvm.memory.pool.name", "G1 Old Gen");
		gauge(registry, "jvm.cpu.recent_utilization", 0.34);
		gauge(registry, "jvm.cpu.count", 8);

		InstanceMetric metric = sourceFor(registry).read();

		assertThat(metric.jvm().heapUsedBytes()).isEqualTo(1000);
		assertThat(metric.jvm().heapMaxBytes()).isEqualTo(2000);
		assertThat(metric.jvm().nonHeapUsedBytes()).isEqualTo(50);
		assertThat(metric.system().processCpuUsage()).isEqualTo(0.34, offset(0.0001));
		assertThat(metric.system().cpuCount()).isEqualTo(8);
	}

	@Test
	void countsTheMemoryOnceWhenBothConventionsArePublished() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		gauge(registry, "jvm.memory.used", 1000, "area", "heap", "id", "old");
		gauge(registry, "jvm.memory.used", 1000, "jvm.memory.type", "heap", "jvm.memory.pool.name", "G1 Old Gen");

		// The same pool published twice must not read as twice the memory.
		assertThat(sourceFor(registry).read().jvm().heapUsedBytes()).isEqualTo(1000);
	}

	@Test
	void readsTheUptimeAndTheSystemFigures() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		TimeGauge.builder("process.uptime", new AtomicLong(90_000), TimeUnit.MILLISECONDS, AtomicLong::get)
			.register(registry);
		gauge(registry, "process.cpu.usage", 0.34);
		gauge(registry, "system.cpu.usage", 0.51);
		gauge(registry, "system.cpu.count", 8);
		gauge(registry, "system.load.average.1m", 2.5);
		gauge(registry, "process.files.open", 210);
		gauge(registry, "process.files.max", 10240);

		InstanceMetric metric = sourceFor(registry).read();

		assertThat(metric.uptimeSeconds()).isEqualTo(90);
		assertThat(metric.system().processCpuUsage()).isEqualTo(0.34, offset(0.0001));
		assertThat(metric.system().systemCpuUsage()).isEqualTo(0.51, offset(0.0001));
		assertThat(metric.system().cpuCount()).isEqualTo(8);
		assertThat(metric.system().loadAverage1m()).isEqualTo(2.5, offset(0.0001));
		assertThat(metric.system().openFiles()).isEqualTo(210);
		assertThat(metric.system().maxFiles()).isEqualTo(10240);
	}

	@Test
	void sumsThePendingTasksOfEveryEventLoop() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		gauge(registry, LocalInstanceMetricsSource.EVENT_LOOP_PENDING_TASKS, 3, "name", "loop-1");
		gauge(registry, LocalInstanceMetricsSource.EVENT_LOOP_PENDING_TASKS, 5, "name", "loop-2");

		InstanceMetric.NettyStats netty = sourceFor(registry).read().netty();

		assertThat(netty.eventLoopPendingTasks()).isEqualTo(8);
		assertThat(netty.eventLoops()).isEqualTo(2);
	}

	@Test
	void foldsThePoolCountersPerProviderAndDownstreamAddress() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		String prefix = LocalInstanceMetricsSource.POOL_PREFIX;
		// Two pool instances towards the same downstream: one row, counters summed.
		gauge(registry, prefix + "active.connections", 20, "name", "proxy", "remote.address", "service-a:8080", "id",
				"1");
		gauge(registry, prefix + "active.connections", 27, "name", "proxy", "remote.address", "service-a:8080", "id",
				"2");
		gauge(registry, prefix + "idle.connections", 3, "name", "proxy", "remote.address", "service-a:8080", "id", "1");
		gauge(registry, prefix + "pending.connections", 12, "name", "proxy", "remote.address", "service-a:8080", "id",
				"1");
		gauge(registry, prefix + "max.connections", 50, "name", "proxy", "remote.address", "service-a:8080", "id", "1");
		gauge(registry, prefix + "max.pending.connections", 100, "name", "proxy", "remote.address", "service-a:8080",
				"id", "1");
		Timer.builder(prefix + "pending.connections.time")
			.tags("name", "proxy", "remote.address", "service-a:8080")
			.register(registry)
			.record(340, TimeUnit.MILLISECONDS);
		gauge(registry, prefix + "active.connections", 2, "name", "proxy", "remote.address", "service-b:8443", "id",
				"3");

		var pools = sourceFor(registry).read().pools();

		assertThat(pools).hasSize(2);
		PoolStats serviceA = pools.stream()
			.filter((pool) -> pool.remoteAddress().equals("service-a:8080"))
			.findFirst()
			.orElseThrow();
		assertThat(serviceA.name()).isEqualTo("proxy");
		assertThat(serviceA.active()).isEqualTo(47.0, offset(0.001));
		assertThat(serviceA.idle()).isEqualTo(3.0, offset(0.001));
		assertThat(serviceA.pending()).isEqualTo(12.0, offset(0.001));
		assertThat(serviceA.max()).isEqualTo(50.0, offset(0.001));
		assertThat(serviceA.maxPending()).isEqualTo(100.0, offset(0.001));
		assertThat(serviceA.pendingTimeAvgMs()).isEqualTo(340.0, offset(1.0));
	}

	@Test
	void reportsWhichInstrumentationIsOn() {
		HttpClientProperties httpClientProperties = new HttpClientProperties();
		httpClientProperties.getPool().setMetrics(true);
		MetricsProperties properties = new MetricsProperties();
		properties.getInstance().setInstrumentHttpClient(true);

		InstanceMetric.InstanceInstrumentation instrumentation = sourceFor(new SimpleMeterRegistry(), properties,
				httpClientProperties)
			.read()
			.instrumentation();

		assertThat(instrumentation.connectionPool()).isTrue();
		assertThat(instrumentation.httpClient()).isTrue();
	}

	@Test
	void separatesPoolCountersBeingOffFromNoDownstreamCalledYet() {
		// Both cases show an empty pool list; only the flag tells them apart, and they
		// call for opposite actions.
		InstanceMetric.InstanceInstrumentation instrumentation = sourceFor(new SimpleMeterRegistry()).read()
			.instrumentation();

		assertThat(instrumentation.connectionPool()).isFalse();
		assertThat(instrumentation.httpClient()).isFalse();
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsAnEmptyRowWhenNoRegistryIsAvailable() {
		ObjectProvider<MeterRegistry> registryProvider = mock(ObjectProvider.class);
		when(registryProvider.getIfAvailable()).thenReturn(null);
		ObjectProvider<HttpClientProperties> httpClientProvider = mock(ObjectProvider.class);
		when(httpClientProvider.getIfAvailable()).thenReturn(null);
		LocalInstanceMetricsSource source = new LocalInstanceMetricsSource(registryProvider, httpClientProvider,
				new MetricsProperties(), new InstanceIdentity("gateway-1"));

		InstanceMetric metric = source.read();

		assertThat(metric.instanceId()).isEqualTo("gateway-1");
		assertThat(metric.pools()).isEmpty();
		assertThat(metric.jvm().heapUsedBytes()).isEqualTo(-1);
		assertThat(metric.instrumentation().connectionPool()).isFalse();
	}

}
