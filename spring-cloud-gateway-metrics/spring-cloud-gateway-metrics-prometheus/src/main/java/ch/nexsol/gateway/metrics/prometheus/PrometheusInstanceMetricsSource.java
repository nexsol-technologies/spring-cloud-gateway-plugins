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

package ch.nexsol.gateway.metrics.prometheus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetric.InstanceInstrumentation;
import ch.nexsol.gateway.metrics.InstanceMetric.JvmStats;
import ch.nexsol.gateway.metrics.InstanceMetric.NettyStats;
import ch.nexsol.gateway.metrics.InstanceMetric.PoolStats;
import ch.nexsol.gateway.metrics.InstanceMetric.SystemStats;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.metrics.prometheus.PrometheusQueryResponse.Sample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * Reads the technical figures of every instance from Prometheus.
 * <p>
 * One instant query, not one per counter: the series are selected by name through a
 * {@code __name__} matcher and grouped per instance here. Reading twenty counters from a
 * metrics backend is otherwise twenty round trips, which would make this by far the most
 * expensive source instead of the cheapest.
 * <p>
 * The query is wrapped in {@code last_over_time} so an instance that stopped reporting
 * drops out on its own. Prometheus keeps the series of instances that no longer exist
 * &mdash; the very property that makes it the best source for route figures, since a
 * rolling restart does not reset them &mdash; and a list of instances is meant to name
 * the ones running now.
 */
public class PrometheusInstanceMetricsSource implements InstanceMetricsSource {

	private static final Logger LOG = LoggerFactory.getLogger(PrometheusInstanceMetricsSource.class);

	private static final String NAME = "__name__";

	private static final String AREA = "area";

	private static final String HEAP = "heap";

	private static final String NON_HEAP = "nonheap";

	private static final String POOL_NAME = "name";

	private static final String REMOTE_ADDRESS = "remote_address";

	private static final String UPTIME = "process_uptime_seconds";

	private static final String MEMORY_USED = "jvm_memory_used_bytes";

	private static final String MEMORY_MAX = "jvm_memory_max_bytes";

	private static final String GC_OVERHEAD = "jvm_gc_overhead";

	private static final String GC_PAUSE_COUNT = "jvm_gc_pause_seconds_count";

	private static final String GC_PAUSE_SUM = "jvm_gc_pause_seconds_sum";

	private static final String THREADS_LIVE = "jvm_threads_live_threads";

	private static final String THREADS_PEAK = "jvm_threads_peak_threads";

	private static final String THREADS_DAEMON = "jvm_threads_daemon_threads";

	private static final String PROCESS_CPU = "process_cpu_usage";

	private static final String SYSTEM_CPU = "system_cpu_usage";

	private static final String LOAD_AVERAGE = "system_load_average_1m";

	private static final String CPU_COUNT = "system_cpu_count";

	private static final String FILES_OPEN = "process_files_open_files";

	private static final String FILES_MAX = "process_files_max_files";

	private static final String EVENT_LOOP_PENDING = "reactor_netty_eventloop_pending_tasks";

	private static final String POOL_PREFIX = "reactor_netty_connection_provider_";

	private static final String POOL_ACTIVE = POOL_PREFIX + "active_connections";

	private static final String POOL_IDLE = POOL_PREFIX + "idle_connections";

	private static final String POOL_PENDING = POOL_PREFIX + "pending_connections";

	private static final String POOL_MAX = POOL_PREFIX + "max_connections";

	private static final String POOL_MAX_PENDING = POOL_PREFIX + "max_pending_connections";

	private static final String POOL_WAIT_COUNT = POOL_PREFIX + "pending_connections_time_seconds_count";

	private static final String POOL_WAIT_SUM = POOL_PREFIX + "pending_connections_time_seconds_sum";

	private static final List<String> METRIC_NAMES = List.of(UPTIME, MEMORY_USED, MEMORY_MAX, GC_OVERHEAD,
			GC_PAUSE_COUNT, GC_PAUSE_SUM, THREADS_LIVE, THREADS_PEAK, THREADS_DAEMON, PROCESS_CPU, SYSTEM_CPU,
			LOAD_AVERAGE, CPU_COUNT, FILES_OPEN, FILES_MAX, EVENT_LOOP_PENDING, POOL_ACTIVE, POOL_IDLE, POOL_PENDING,
			POOL_MAX, POOL_MAX_PENDING, POOL_WAIT_COUNT, POOL_WAIT_SUM);

	private static final long MISSING = -1L;

	private final WebClient webClient;

	private final PrometheusMetricsProperties properties;

	/**
	 * Creates the source querying the configured Prometheus.
	 * @param webClient the client used to query Prometheus
	 * @param properties the Prometheus configuration
	 */
	public PrometheusInstanceMetricsSource(WebClient webClient, PrometheusMetricsProperties properties) {
		this.webClient = webClient;
		this.properties = properties;
	}

	@Override
	public Mono<InstanceMetricsSnapshot> collect() {
		return query(expression()).map(this::toSnapshot).onErrorResume((ex) -> {
			LOG.warn("Could not read the instance metrics from Prometheus at {}: {}", this.properties.getUrl(),
					ex.getMessage());
			return Mono.just(InstanceMetricsSnapshot
				.empty("every instance, from Prometheus — " + PrometheusRouteMetricsSource.reason(ex)));
		});
	}

	private String expression() {
		// Declaring the property with no value binds it to null rather than to the empty
		// default, and this runs outside the reactive chain: a failure here would escape
		// the error handling above and break the page instead of reporting no data.
		String selector = this.properties.getSelector();
		String matchers = NAME + "=~\"" + String.join("|", METRIC_NAMES) + "\""
				+ ((selector == null || selector.isBlank()) ? "" : ("," + selector));
		return "last_over_time({" + matchers + "}[" + this.properties.getStaleAfter().toSeconds() + "s])";
	}

	private Mono<List<Sample>> query(String expression) {
		return this.webClient.get()
			// The expression is passed as a URI variable rather than inlined: a PromQL
			// selector is written between braces, which the URI builder would otherwise
			// read as a template placeholder and fail to expand.
			.uri((builder) -> builder.path("/api/v1/query").queryParam("query", "{query}").build(expression))
			.retrieve()
			.bodyToMono(PrometheusQueryResponse.class)
			.timeout(this.properties.getTimeout())
			.map((response) -> (response.data() != null && response.data().result() != null) ? response.data().result()
					: List.<Sample>of());
	}

	private InstanceMetricsSnapshot toSnapshot(List<Sample> samples) {
		Map<String, List<Sample>> byInstance = new LinkedHashMap<>();
		for (Sample sample : samples) {
			String instanceId = sample.label(this.properties.getInstanceLabel());
			if (instanceId != null) {
				byInstance.computeIfAbsent(instanceId, (key) -> new ArrayList<>()).add(sample);
			}
		}
		List<InstanceMetric> instances = byInstance.entrySet()
			.stream()
			.map((entry) -> toInstance(entry.getKey(), entry.getValue()))
			.sorted(Comparator.comparing(InstanceMetric::instanceId))
			.toList();
		return new InstanceMetricsSnapshot(coverage(instances.size()), instances);
	}

	private static String coverage(int instances) {
		if (instances == 0) {
			return "no instance reported to Prometheus recently";
		}
		return instances + ((instances == 1) ? " instance" : " instances") + ", from Prometheus";
	}

	private static InstanceMetric toInstance(String instanceId, List<Sample> samples) {
		List<PoolStats> pools = pools(samples);
		JvmStats jvm = new JvmStats(bytes(sum(samples, MEMORY_USED, AREA, HEAP)),
				bytes(sum(samples, MEMORY_MAX, AREA, HEAP)), bytes(sum(samples, MEMORY_USED, AREA, NON_HEAP)),
				orZero(sum(samples, GC_OVERHEAD)), orZero(sum(samples, GC_PAUSE_SUM)) * 1000.0,
				(long) orZero(sum(samples, GC_PAUSE_COUNT)), (int) orZero(sum(samples, THREADS_LIVE)),
				(int) orZero(sum(samples, THREADS_PEAK)), (int) orZero(sum(samples, THREADS_DAEMON)));
		SystemStats system = new SystemStats(orZero(sum(samples, PROCESS_CPU)), orZero(sum(samples, SYSTEM_CPU)),
				orMissing(sum(samples, LOAD_AVERAGE)), (int) orZero(sum(samples, CPU_COUNT)),
				bytes(sum(samples, FILES_OPEN)), bytes(sum(samples, FILES_MAX)));
		NettyStats netty = new NettyStats((long) orZero(sum(samples, EVENT_LOOP_PENDING)),
				count(samples, EVENT_LOOP_PENDING));
		// Prometheus cannot be asked how a remote instance was configured, so what is
		// reported is whether the counters reached the backend at all. That is the same
		// answer for the view: absent series and disabled instrumentation are indeed one
		// and the same situation from here.
		InstanceInstrumentation instrumentation = new InstanceInstrumentation(!pools.isEmpty(),
				count(samples, EVENT_LOOP_PENDING) > 0);
		return new InstanceMetric(instanceId, null, (long) orZero(sum(samples, UPTIME)), jvm, system, netty, pools,
				instrumentation);
	}

	private static List<PoolStats> pools(List<Sample> samples) {
		Map<String, PoolAccumulator> byPool = new LinkedHashMap<>();
		for (Sample sample : samples) {
			String name = sample.label(NAME);
			if (name == null || !name.startsWith(POOL_PREFIX)) {
				continue;
			}
			String pool = labelOrEmpty(sample, POOL_NAME);
			String remoteAddress = labelOrEmpty(sample, REMOTE_ADDRESS);
			byPool.computeIfAbsent(pool + '|' + remoteAddress, (key) -> new PoolAccumulator(pool, remoteAddress))
				.add(name, sample.doubleValue());
		}
		return byPool.values().stream().map(PoolAccumulator::toStats).toList();
	}

	private static String labelOrEmpty(Sample sample, String label) {
		String value = sample.label(label);
		return (value != null) ? value : "";
	}

	private static double sum(List<Sample> samples, String metric) {
		return sum(samples, metric, null, null);
	}

	/**
	 * Sums the series of one metric, optionally restricted to one label value. Returns
	 * {@code NaN} when the metric is absent, which is what tells "not published" apart
	 * from "zero". A negative reading is left out for the same reason it is locally: an
	 * unbounded memory pool reports {@code -1} and must not shrink the ceiling of the
	 * pools that have one.
	 */
	private static double sum(List<Sample> samples, String metric, String label, String value) {
		double total = Double.NaN;
		for (Sample sample : samples) {
			if (!metric.equals(sample.label(NAME))) {
				continue;
			}
			if (label != null && !value.equals(sample.label(label))) {
				continue;
			}
			double read = sample.doubleValue();
			if (read >= 0) {
				total = Double.isNaN(total) ? read : total + read;
			}
		}
		return total;
	}

	private static int count(List<Sample> samples, String metric) {
		return (int) samples.stream().filter((sample) -> metric.equals(sample.label(NAME))).count();
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

	/** Mutable per-pool accumulator, folding the series of every pool instance. */
	private static final class PoolAccumulator {

		private final String name;

		private final String remoteAddress;

		private double active;

		private double idle;

		private double pending;

		private double max;

		private double maxPending;

		private double waitSeconds;

		private double waitCount;

		private PoolAccumulator(String name, String remoteAddress) {
			this.name = name;
			this.remoteAddress = remoteAddress;
		}

		private void add(String metric, double value) {
			switch (metric) {
				case POOL_ACTIVE -> this.active += value;
				case POOL_IDLE -> this.idle += value;
				case POOL_PENDING -> this.pending += value;
				case POOL_MAX -> this.max += value;
				case POOL_MAX_PENDING -> this.maxPending += value;
				case POOL_WAIT_SUM -> this.waitSeconds += value;
				case POOL_WAIT_COUNT -> this.waitCount += value;
				default -> {
					// A pool counter this view does not show, such as the HTTP/2 stream
					// gauges: skipped rather than folded into an unrelated figure.
				}
			}
		}

		private PoolStats toStats() {
			double average = (this.waitCount > 0) ? (this.waitSeconds * 1000.0) / this.waitCount : 0.0;
			return new PoolStats(this.name, this.remoteAddress, this.active, this.idle, this.pending, this.max,
					this.maxPending, average);
		}

	}

}
