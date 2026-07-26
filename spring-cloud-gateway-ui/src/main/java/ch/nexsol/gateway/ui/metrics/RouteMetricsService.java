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

package ch.nexsol.gateway.ui.metrics;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Aggregates the Spring Cloud Gateway per-request timer
 * ({@code spring.cloud.gateway.requests}) into one {@link RouteMetric} per route, feeding
 * the traffic bubble chart.
 * <p>
 * The {@link MeterRegistry} is resolved lazily and optionally: when none is present the
 * service simply reports no data instead of failing.
 */
public class RouteMetricsService {

	/** Name of the timer published by the gateway metrics filter for each request. */
	static final String REQUESTS_METER = "spring.cloud.gateway.requests";

	private final ObjectProvider<MeterRegistry> meterRegistry;

	/**
	 * Creates the service reading from the (optional) meter registry.
	 * @param meterRegistry the provider over the application meter registry
	 */
	public RouteMetricsService(ObjectProvider<MeterRegistry> meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Collects the current per-route metrics, ordered by request count descending.
	 * @return the aggregated metrics, empty when no registry or no traffic is available
	 */
	public List<RouteMetric> collect() {
		MeterRegistry registry = this.meterRegistry.getIfAvailable();
		if (registry == null) {
			return List.of();
		}
		Map<String, Accumulator> byRoute = new LinkedHashMap<>();
		for (Timer timer : registry.find(REQUESTS_METER).timers()) {
			String routeId = timer.getId().getTag("routeId");
			if (routeId == null || routeId.isBlank()) {
				continue;
			}
			Accumulator accumulator = byRoute.computeIfAbsent(routeId, (key) -> new Accumulator());
			if (accumulator.uri == null) {
				accumulator.uri = timer.getId().getTag("routeUri");
			}
			long count = timer.count();
			accumulator.count += count;
			accumulator.totalMs += timer.totalTime(TimeUnit.MILLISECONDS);
			accumulator.maxMs = Math.max(accumulator.maxMs, timer.max(TimeUnit.MILLISECONDS));
			if (isServerError(timer.getId().getTag("httpStatusCode"))) {
				accumulator.errorCount += count;
			}
		}
		return byRoute.entrySet()
			.stream()
			.map((entry) -> entry.getValue().toMetric(entry.getKey()))
			.sorted(Comparator.comparingLong(RouteMetric::count).reversed())
			.toList();
	}

	private static boolean isServerError(String httpStatusCode) {
		return httpStatusCode != null && httpStatusCode.length() == 3 && httpStatusCode.charAt(0) == '5';
	}

	/**
	 * Mutable per-route accumulator used while summing the individual status/method
	 * timers back into a single data point.
	 */
	private static final class Accumulator {

		private String uri;

		private long count;

		private double totalMs;

		private double maxMs;

		private long errorCount;

		private RouteMetric toMetric(String routeId) {
			double avgMs = (this.count > 0) ? this.totalMs / this.count : 0.0;
			double errorRate = (this.count > 0) ? (double) this.errorCount / this.count : 0.0;
			return new RouteMetric(routeId, this.uri, this.count, avgMs, this.maxMs, this.errorCount, errorRate);
		}

	}

}
