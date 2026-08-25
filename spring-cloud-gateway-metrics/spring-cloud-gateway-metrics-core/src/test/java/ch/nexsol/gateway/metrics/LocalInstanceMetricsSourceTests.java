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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.metrics.InstanceMetric.PoolStats;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterConvention;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.convention.JvmCpuMeterConventions;
import io.micrometer.core.instrument.binder.jvm.convention.JvmMemoryMeterConventions;
import io.micrometer.core.instrument.binder.jvm.convention.micrometer.MicrometerJvmCpuMeterConventions;
import io.micrometer.core.instrument.binder.jvm.convention.micrometer.MicrometerJvmMemoryMeterConventions;
import io.micrometer.core.instrument.binder.jvm.convention.otel.OpenTelemetryJvmCpuMeterConventions;
import io.micrometer.core.instrument.binder.jvm.convention.otel.OpenTelemetryJvmMemoryMeterConventions;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.config.HttpClientProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LocalInstanceMetricsSource}.
 */
class LocalInstanceMetricsSourceTests {

	/**
	 * The two conventions Micrometer ships. The Micrometer case declares no bean, which
	 * is what makes the binders fall back to {@code MicrometerJvm*MeterConventions}; the
	 * OpenTelemetry case declares both.
	 */
	static List<Conventions> conventions() {
		return List.of(
				new Conventions("micrometer", null, null, new MicrometerJvmMemoryMeterConventions(Tags.empty()),
						new MicrometerJvmCpuMeterConventions(Tags.empty())),
				new Conventions("opentelemetry", new OpenTelemetryJvmMemoryMeterConventions(Tags.empty()),
						new OpenTelemetryJvmCpuMeterConventions(Tags.empty()),
						new OpenTelemetryJvmMemoryMeterConventions(Tags.empty()),
						new OpenTelemetryJvmCpuMeterConventions(Tags.empty())));
	}

	private LocalInstanceMetricsSource sourceFor(MeterRegistry registry) {
		return sourceFor(registry, new MetricsProperties(), new HttpClientProperties(), null, null);
	}

	private LocalInstanceMetricsSource sourceFor(MeterRegistry registry, Conventions conventions) {
		return sourceFor(registry, new MetricsProperties(), new HttpClientProperties(), conventions.declaredMemory(),
				conventions.declaredCpu());
	}

	private LocalInstanceMetricsSource sourceFor(MeterRegistry registry, MetricsProperties properties,
			HttpClientProperties httpClientProperties) {
		return sourceFor(registry, properties, httpClientProperties, null, null);
	}

	@SuppressWarnings("unchecked")
	private LocalInstanceMetricsSource sourceFor(MeterRegistry registry, MetricsProperties properties,
			HttpClientProperties httpClientProperties, JvmMemoryMeterConventions memory, JvmCpuMeterConventions cpu) {
		ObjectProvider<MeterRegistry> registryProvider = mock(ObjectProvider.class);
		when(registryProvider.getIfAvailable()).thenReturn(registry);
		ObjectProvider<HttpClientProperties> httpClientProvider = mock(ObjectProvider.class);
		when(httpClientProvider.getIfAvailable()).thenReturn(httpClientProperties);
		return new LocalInstanceMetricsSource(registryProvider, httpClientProvider, provider(memory), provider(cpu),
				properties, new InstanceIdentity("gateway-1"));
	}

	/** A provider over the declared bean, empty when the application declares none. */
	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable(any(Supplier.class)))
			.thenAnswer((invocation) -> (bean != null) ? bean : invocation.getArgument(0, Supplier.class).get());
		return provider;
	}

	private static Gauge gauge(MeterRegistry registry, String name, double value, String... tags) {
		return Gauge.builder(name, () -> value).tags(tags).register(registry);
	}

	/** The pools of one area, in the order the binder walks them. */
	private static List<MemoryPoolMXBean> pools(MemoryType type) {
		List<MemoryPoolMXBean> pools = new ArrayList<>();
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() == type) {
				pools.add(pool);
			}
		}
		return pools;
	}

	/**
	 * Publishes one memory figure per pool the way the binder does: name and tags taken
	 * from the convention. The values are handed out in order and the last one repeats,
	 * so the expected total is known whatever number of pools the running collector has.
	 */
	private static double publishMemory(MeterRegistry registry, MeterConvention<MemoryPoolMXBean> convention,
			MemoryType type, double... values) {
		List<MemoryPoolMXBean> pools = pools(type);
		double expected = 0;
		for (int i = 0; i < pools.size(); i++) {
			double value = values[Math.min(i, values.length - 1)];
			Gauge.builder(convention.getName(), () -> value).tags(convention.getTags(pools.get(i))).register(registry);
			if (value >= 0) {
				expected += value;
			}
		}
		return expected;
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
		gauge(registry, "jvm.threads.live", 87);
		gauge(registry, "jvm.threads.peak", 112);
		gauge(registry, "jvm.threads.daemon", 40);
		gauge(registry, "jvm.gc.overhead", 0.04);
		Timer.builder("jvm.gc.pause")
			.tag("action", "end of minor GC")
			.register(registry)
			.record(12, TimeUnit.MILLISECONDS);

		InstanceMetric.JvmStats jvm = sourceFor(registry).read().jvm();

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

	@ParameterizedTest
	@MethodSource("conventions")
	void readsTheMemoryAndProcessorFiguresUnderEitherConvention(Conventions conventions) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		double heap = publishMemory(registry, conventions.memory().getMemoryUsedConvention(), MemoryType.HEAP, 300,
				700);
		double nonHeap = publishMemory(registry, conventions.memory().getMemoryUsedConvention(), MemoryType.NON_HEAP,
				50);
		double max = publishMemory(registry, conventions.memory().getMemoryMaxConvention(), MemoryType.HEAP, 2000);
		gauge(registry, conventions.cpu().processCpuLoadConvention().getName(), 0.34);
		gauge(registry, conventions.cpu().cpuCountConvention().getName(), 8);

		InstanceMetric metric = sourceFor(registry, conventions).read();

		assertThat(metric.jvm().heapUsedBytes()).isEqualTo((long) heap);
		assertThat(metric.jvm().heapMaxBytes()).isEqualTo((long) max);
		assertThat(metric.jvm().nonHeapUsedBytes()).isEqualTo((long) nonHeap);
		assertThat(metric.system().processCpuUsage()).isEqualTo(0.34, offset(0.0001));
		assertThat(metric.system().cpuCount()).isEqualTo(8);
	}

	@ParameterizedTest
	@MethodSource("conventions")
	void leavesAnUnboundedMemoryPoolOutOfTheHeapCeiling(Conventions conventions) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		double expected = publishMemory(registry, conventions.memory().getMemoryMaxConvention(), MemoryType.HEAP, 2000,
				-1);

		// Adding the -1 of the unbounded pool would silently shrink the ceiling.
		assertThat(sourceFor(registry, conventions).read().jvm().heapMaxBytes()).isEqualTo((long) expected);
	}

	@ParameterizedTest
	@MethodSource("conventions")
	void readsOnlyTheConventionInForceWhenBothArePublished(Conventions conventions) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Conventions other = conventions().stream()
			.filter((candidate) -> !candidate.name().equals(conventions.name()))
			.findFirst()
			.orElseThrow();
		double expected = publishMemory(registry, conventions.memory().getMemoryUsedConvention(), MemoryType.HEAP,
				1000);
		publishMemory(registry, other.memory().getMemoryUsedConvention(), MemoryType.HEAP, 1000);

		// Both conventions name the figure 'jvm.memory.used' and differ only by their
		// area
		// tag, so a registry carrying the two sets holds two gauges per pool.
		assertThat(sourceFor(registry, conventions).read().jvm().heapUsedBytes()).isEqualTo((long) expected);
	}

	@ParameterizedTest
	@MethodSource("conventions")
	void agreesWithTheBinderItReadsUnderEitherConvention(Conventions conventions) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		new JvmMemoryMetrics(Tags.empty(), conventions.memory()).bindTo(registry);
		new ProcessorMetrics(Tags.empty(), conventions.cpu()).bindTo(registry);

		InstanceMetric metric = sourceFor(registry, conventions).read();

		// The gauges come from the binder itself, so the names and tags asserted here are
		// the ones Micrometer actually publishes, not a copy of them.
		assertThat(metric.jvm().heapUsedBytes()).isPositive();
		assertThat(metric.jvm().nonHeapUsedBytes()).isPositive();
		assertThat(metric.jvm().heapMaxBytes()).isPositive();
		assertThat(metric.system().cpuCount()).isPositive();
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
				provider(null), provider(null), new MetricsProperties(), new InstanceIdentity("gateway-1"));

		InstanceMetric metric = source.read();

		assertThat(metric.instanceId()).isEqualTo("gateway-1");
		assertThat(metric.pools()).isEmpty();
		assertThat(metric.jvm().heapUsedBytes()).isEqualTo(-1);
		assertThat(metric.instrumentation().connectionPool()).isFalse();
	}

	/**
	 * One convention case: what the application declares (null for the default) and what
	 * the binder therefore publishes under.
	 */
	record Conventions(String name, JvmMemoryMeterConventions declaredMemory, JvmCpuMeterConventions declaredCpu,
			JvmMemoryMeterConventions memory, JvmCpuMeterConventions cpu) {

		@Override
		public String toString() {
			return this.name;
		}

	}

}
