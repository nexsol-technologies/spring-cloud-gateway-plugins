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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.metrics.InstanceMetric.InstanceInstrumentation;
import ch.nexsol.gateway.metrics.InstanceMetric.JvmStats;
import ch.nexsol.gateway.metrics.InstanceMetric.NettyStats;
import ch.nexsol.gateway.metrics.InstanceMetric.PoolStats;
import ch.nexsol.gateway.metrics.InstanceMetric.SystemStats;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterConvention;
import io.micrometer.core.instrument.binder.jvm.convention.JvmCpuMeterConventions;
import io.micrometer.core.instrument.binder.jvm.convention.JvmMemoryMeterConventions;
import io.micrometer.core.instrument.binder.jvm.convention.micrometer.MicrometerJvmCpuMeterConventions;
import io.micrometer.core.instrument.binder.jvm.convention.micrometer.MicrometerJvmMemoryMeterConventions;
import io.micrometer.core.instrument.binder.jvm.convention.otel.OpenTelemetryJvmCpuMeterConventions;
import io.micrometer.core.instrument.binder.jvm.convention.otel.OpenTelemetryJvmMemoryMeterConventions;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.config.HttpClientProperties;

/**
 * Reads the technical figures of the running instance from its meter registry.
 * <p>
 * The JVM and system figures are always there: Spring Boot binds them out of the box. The
 * Reactor Netty figures are not &mdash; the gateway leaves that instrumentation off
 * &mdash; so what is missing is reported as such through {@link InstanceInstrumentation}
 * instead of being shown as an empty table.
 * <p>
 * The two meter conventions Micrometer ships name memory and processor differently
 * ({@code jvm.memory.max} against {@code jvm.memory.limit}, {@code process.cpu.usage}
 * against {@code jvm.cpu.recent_utilization}, the {@code area} tag against
 * {@code jvm.memory.type}). {@code JvmMemoryMetrics} and {@code ProcessorMetrics} publish
 * under the {@link JvmMemoryMeterConventions} / {@link JvmCpuMeterConventions} bean the
 * application declares, and under the Micrometer ones when it declares none.
 * <p>
 * That bean is tried first and both conventions after it, because it only describes the
 * binders this application configures: an OpenTelemetry agent or an OTLP bridge fills the
 * registry without one, and the figures would then be read as missing. The second lookup
 * only runs when the first finds nothing. Every other figure this class reads is named by
 * the binders themselves, not by a convention.
 */
public class LocalInstanceMetricsSource implements InstanceMetricsSource {

	/** Reactor Netty tag naming the connection provider a pool counter belongs to. */
	public static final String POOL_NAME_TAG = "name";

	/** Reactor Netty tag naming the downstream address a pool connects to. */
	public static final String REMOTE_ADDRESS_TAG = "remote.address";

	/** Prefix shared by every Reactor Netty connection pool counter. */
	public static final String POOL_PREFIX = "reactor.netty.connection.provider.";

	/** Counter of the tasks queued on a Reactor Netty event loop. */
	public static final String EVENT_LOOP_PENDING_TASKS = "reactor.netty.eventloop.pending.tasks";

	private static final long MISSING = -1L;

	private static final Set<String> FIXED_METER_NAMES = Set.of("process.uptime", "jvm.gc.pause", "jvm.gc.overhead",
			"jvm.threads.live", "jvm.threads.peak", "jvm.threads.daemon", "system.cpu.usage", "system.load.average.1m",
			"process.files.open", "process.files.max", EVENT_LOOP_PENDING_TASKS, POOL_PREFIX + "active.connections",
			POOL_PREFIX + "idle.connections", POOL_PREFIX + "pending.connections", POOL_PREFIX + "max.connections",
			POOL_PREFIX + "max.pending.connections", POOL_PREFIX + "pending.connections.time");

	private final ObjectProvider<MeterRegistry> meterRegistry;

	private final ObjectProvider<HttpClientProperties> httpClientProperties;

	private final List<MeterConvention<MemoryPoolMXBean>> memoryUsed;

	private final List<MeterConvention<MemoryPoolMXBean>> memoryMax;

	private final List<String> processCpuLoad;

	private final List<String> cpuCount;

	private final boolean httpClientInstrumented;

	private final String instanceId;

	private final String coverage;

	private final Set<String> meterNames;

	/**
	 * Creates the source reading from the (optional) meter registry.
	 * @param meterRegistry the provider over the application meter registry
	 * @param httpClientProperties the provider over the gateway HTTP client
	 * configuration, read to tell whether the pool counters are collected
	 * @param memoryConventions the memory meter conventions declared by the application;
	 * absent, {@code JvmMemoryMetrics} binds under the Micrometer ones and so does this
	 * source
	 * @param cpuConventions the processor meter conventions declared by the application;
	 * absent, {@code ProcessorMetrics} binds under the Micrometer ones
	 * @param properties the metrics configuration
	 * @param identity the identity of the running instance
	 */
	public LocalInstanceMetricsSource(ObjectProvider<MeterRegistry> meterRegistry,
			ObjectProvider<HttpClientProperties> httpClientProperties,
			ObjectProvider<JvmMemoryMeterConventions> memoryConventions,
			ObjectProvider<JvmCpuMeterConventions> cpuConventions, MetricsProperties properties,
			InstanceIdentity identity) {
		this.meterRegistry = meterRegistry;
		this.httpClientProperties = httpClientProperties;
		List<JvmMemoryMeterConventions> memory = new ArrayList<>(3);
		memoryConventions.ifAvailable(memory::add);
		memory.add(new MicrometerJvmMemoryMeterConventions(Tags.empty()));
		memory.add(new OpenTelemetryJvmMemoryMeterConventions(Tags.empty()));
		this.memoryUsed = memory.stream().map(JvmMemoryMeterConventions::getMemoryUsedConvention).toList();
		this.memoryMax = memory.stream().map(JvmMemoryMeterConventions::getMemoryMaxConvention).toList();
		List<JvmCpuMeterConventions> cpu = new ArrayList<>(3);
		cpuConventions.ifAvailable(cpu::add);
		cpu.add(new MicrometerJvmCpuMeterConventions(Tags.empty()));
		cpu.add(new OpenTelemetryJvmCpuMeterConventions(Tags.empty()));
		this.processCpuLoad = cpu.stream().map((c) -> c.processCpuLoadConvention().getName()).toList();
		this.cpuCount = cpu.stream().map((c) -> c.cpuCountConvention().getName()).toList();
		this.httpClientInstrumented = properties.getInstance().isInstrumentHttpClient();
		this.instanceId = identity.id();
		this.coverage = "this instance only (" + identity.id() + ")";
		Set<String> names = new HashSet<>(FIXED_METER_NAMES);
		this.memoryUsed.forEach((convention) -> names.add(convention.getName()));
		this.memoryMax.forEach((convention) -> names.add(convention.getName()));
		names.addAll(this.processCpuLoad);
		names.addAll(this.cpuCount);
		this.meterNames = Set.copyOf(names);
	}

	@Override
	public Mono<InstanceMetricsSnapshot> collect() {
		return Mono.fromSupplier(() -> new InstanceMetricsSnapshot(this.coverage, List.of(read())));
	}

	/**
	 * Reads the figures of this instance, left un-merged. Exposed so a provider
	 * consolidating several instances can reuse the local reading as its own row.
	 * @return the figures of this instance
	 */
	public InstanceMetric read() {
		MeterRegistry registry = this.meterRegistry.getIfAvailable();
		if (registry == null) {
			return new InstanceMetric(this.instanceId, null, 0, emptyJvm(), emptySystem(), new NettyStats(0, 0),
					List.of(), instrumentation());
		}
		Meters meters = Meters.of(registry, this.meterNames);
		return new InstanceMetric(this.instanceId, null, uptimeSeconds(meters), jvm(meters), system(meters),
				netty(meters), pools(meters), instrumentation());
	}

	private InstanceInstrumentation instrumentation() {
		HttpClientProperties properties = this.httpClientProperties.getIfAvailable();
		boolean pool = properties != null && properties.getPool().isMetrics();
		return new InstanceInstrumentation(pool, this.httpClientInstrumented);
	}

	private static long uptimeSeconds(Meters meters) {
		TimeGauge uptime = meters.first("process.uptime", TimeGauge.class);
		return (uptime != null) ? (long) uptime.value(TimeUnit.SECONDS) : 0;
	}

	private JvmStats jvm(Meters meters) {
		double pauseTotalMs = 0.0;
		long pauseCount = 0;
		for (Timer timer : meters.all("jvm.gc.pause", Timer.class)) {
			pauseTotalMs += timer.totalTime(TimeUnit.MILLISECONDS);
			pauseCount += timer.count();
		}
		return new JvmStats(bytes(memory(meters, this.memoryUsed, MemoryType.HEAP)),
				bytes(memory(meters, this.memoryMax, MemoryType.HEAP)),
				bytes(memory(meters, this.memoryUsed, MemoryType.NON_HEAP)), orZero(gauge(meters, "jvm.gc.overhead")),
				pauseTotalMs, pauseCount, (int) orZero(gauge(meters, "jvm.threads.live")),
				(int) orZero(gauge(meters, "jvm.threads.peak")), (int) orZero(gauge(meters, "jvm.threads.daemon")));
	}

	private SystemStats system(Meters meters) {
		return new SystemStats(orZero(gauge(meters, this.processCpuLoad)), orZero(gauge(meters, "system.cpu.usage")),
				orMissing(gauge(meters, "system.load.average.1m")), (int) orZero(gauge(meters, this.cpuCount)),
				bytes(gauge(meters, "process.files.open")), bytes(gauge(meters, "process.files.max")));
	}

	/**
	 * Sums one memory figure over the pools of an area, under the first convention that
	 * finds it.
	 * @param registry the registry to read
	 * @param conventions the candidate conventions, most likely first
	 * @param type the memory area to sum over
	 * @return the total bytes, or {@code NaN} when no convention found the figure
	 */
	private static double memory(Meters meters, List<MeterConvention<MemoryPoolMXBean>> conventions, MemoryType type) {
		for (MeterConvention<MemoryPoolMXBean> convention : conventions) {
			double total = memory(meters, convention, type);
			if (!Double.isNaN(total)) {
				return total;
			}
		}
		return Double.NaN;
	}

	/**
	 * Sums one memory figure over the pools of an area.
	 * <p>
	 * The binder registers one gauge per {@link MemoryPoolMXBean}, named
	 * {@code convention.getName()} and tagged {@code convention.getTags(pool)}; the same
	 * pools and the same convention produce the lookup key here. A pool without a ceiling
	 * reports {@code -1}, which must not be added to the pools that have one.
	 * @param registry the registry to read
	 * @param convention the convention to read the figure under
	 * @param type the memory area to sum over
	 * @return the total bytes, or {@code NaN} when no pool published the figure
	 */
	private static double memory(Meters meters, MeterConvention<MemoryPoolMXBean> convention, MemoryType type) {
		double total = Double.NaN;
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() != type) {
				continue;
			}
			Gauge gauge = meters.gauge(convention.getName(), convention.getTags(pool));
			if (gauge != null && gauge.value() >= 0) {
				total = Double.isNaN(total) ? gauge.value() : total + gauge.value();
			}
		}
		return total;
	}

	private static NettyStats netty(Meters meters) {
		long pending = 0;
		int loops = 0;
		for (Gauge loop : meters.all(EVENT_LOOP_PENDING_TASKS, Gauge.class)) {
			pending += (long) loop.value();
			loops++;
		}
		return new NettyStats(pending, loops);
	}

	/**
	 * Folds the pool counters into one row per connection provider and downstream
	 * address.
	 * <p>
	 * The {@code id} tag Reactor Netty also carries identifies a pool instance, and its
	 * cardinality follows the internals of the transport. What an operator reads is "the
	 * pool towards service-a is full", so the rows are keyed on the name and the address
	 * and the instances behind them are summed.
	 */
	private static List<PoolStats> pools(Meters meters) {
		Map<String, PoolAccumulator> byPool = new LinkedHashMap<>();
		for (Gauge value : meters.all(POOL_PREFIX + "active.connections", Gauge.class)) {
			accumulator(byPool, value).active += value.value();
		}
		for (Gauge value : meters.all(POOL_PREFIX + "idle.connections", Gauge.class)) {
			accumulator(byPool, value).idle += value.value();
		}
		for (Gauge value : meters.all(POOL_PREFIX + "pending.connections", Gauge.class)) {
			accumulator(byPool, value).pending += value.value();
		}
		for (Gauge value : meters.all(POOL_PREFIX + "max.connections", Gauge.class)) {
			accumulator(byPool, value).max += value.value();
		}
		for (Gauge value : meters.all(POOL_PREFIX + "max.pending.connections", Gauge.class)) {
			accumulator(byPool, value).maxPending += value.value();
		}
		for (Timer wait : meters.all(POOL_PREFIX + "pending.connections.time", Timer.class)) {
			PoolAccumulator pool = accumulator(byPool, wait);
			pool.pendingTimeMs += wait.totalTime(TimeUnit.MILLISECONDS);
			pool.pendingTimeCount += wait.count();
		}
		List<PoolStats> pools = new ArrayList<>(byPool.size());
		for (PoolAccumulator pool : byPool.values()) {
			pools.add(pool.toStats());
		}
		return pools;
	}

	private static PoolAccumulator accumulator(Map<String, PoolAccumulator> byPool, Meter meter) {
		String name = tagOrEmpty(meter, POOL_NAME_TAG);
		String remoteAddress = tagOrEmpty(meter, REMOTE_ADDRESS_TAG);
		return byPool.computeIfAbsent(name + '|' + remoteAddress, (key) -> new PoolAccumulator(name, remoteAddress));
	}

	private static String tagOrEmpty(Meter meter, String tag) {
		String value = meter.getId().getTag(tag);
		return (value != null) ? value : "";
	}

	private static double gauge(Meters meters, String name) {
		Gauge gauge = meters.first(name, Gauge.class);
		return (gauge != null) ? gauge.value() : Double.NaN;
	}

	/** Reads a gauge under the first of the candidate names that carries it. */
	private static double gauge(Meters meters, List<String> names) {
		for (String name : names) {
			double value = gauge(meters, name);
			if (!Double.isNaN(value)) {
				return value;
			}
		}
		return Double.NaN;
	}

	private static long bytes(double value) {
		return Double.isNaN(value) ? MISSING : (long) value;
	}

	private static double orZero(double value) {
		return Double.isNaN(value) ? 0.0 : value;
	}

	private static double orMissing(double value) {
		return Double.isNaN(value) ? MISSING : value;
	}

	private static JvmStats emptyJvm() {
		return new JvmStats(MISSING, MISSING, MISSING, 0.0, 0.0, 0, 0, 0, 0);
	}

	private static SystemStats emptySystem() {
		return new SystemStats(0.0, 0.0, MISSING, 0, MISSING, MISSING);
	}

	/** Mutable per-pool accumulator, folding the counters of every pool instance. */
	/**
	 * The meters one reading needs, gathered in a single walk of the registry.
	 * <p>
	 * A search of the registry copies and filters every meter it holds, and a gateway
	 * holds one timer per route, status and outcome: searching once per figure made some
	 * thirty walks over all of them for each reading, on every publication and on every
	 * poll of the console.
	 */
	private static final class Meters {

		private final Map<String, List<Meter>> byName;

		private Meters(Map<String, List<Meter>> byName) {
			this.byName = byName;
		}

		static Meters of(MeterRegistry registry, Set<String> names) {
			Map<String, List<Meter>> byName = new HashMap<>();
			for (Meter meter : registry.getMeters()) {
				String name = meter.getId().getName();
				if (names.contains(name)) {
					byName.computeIfAbsent(name, (key) -> new ArrayList<>(4)).add(meter);
				}
			}
			return new Meters(byName);
		}

		<M extends Meter> List<M> all(String name, Class<M> type) {
			List<M> matching = new ArrayList<>();
			for (Meter meter : this.byName.getOrDefault(name, List.of())) {
				if (type.isInstance(meter)) {
					matching.add(type.cast(meter));
				}
			}
			return matching;
		}

		<M extends Meter> M first(String name, Class<M> type) {
			for (Meter meter : this.byName.getOrDefault(name, List.of())) {
				if (type.isInstance(meter)) {
					return type.cast(meter);
				}
			}
			return null;
		}

		Gauge gauge(String name, Iterable<Tag> tags) {
			for (Gauge gauge : all(name, Gauge.class)) {
				if (carries(gauge, tags)) {
					return gauge;
				}
			}
			return null;
		}

		private static boolean carries(Meter meter, Iterable<Tag> tags) {
			for (Tag tag : tags) {
				if (!tag.getValue().equals(meter.getId().getTag(tag.getKey()))) {
					return false;
				}
			}
			return true;
		}

	}

	private static final class PoolAccumulator {

		private final String name;

		private final String remoteAddress;

		private double active;

		private double idle;

		private double pending;

		private double max;

		private double maxPending;

		private double pendingTimeMs;

		private long pendingTimeCount;

		private PoolAccumulator(String name, String remoteAddress) {
			this.name = name;
			this.remoteAddress = remoteAddress;
		}

		private PoolStats toStats() {
			double average = (this.pendingTimeCount > 0) ? this.pendingTimeMs / this.pendingTimeCount : 0.0;
			return new PoolStats(this.name, this.remoteAddress, this.active, this.idle, this.pending, this.max,
					this.maxPending, average);
		}

	}

}
