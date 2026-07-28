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

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds partial figures sharing a route id into one figure per route.
 * <p>
 * Every source needs this: the local one because the gateway publishes one timer per
 * status and method, the consolidating ones because each instance reports its own share.
 * Summing is not merging &mdash; an average of averages is wrong as soon as the parts
 * have different weights, so the totals are rebuilt from the counts before the rates and
 * the latency are recomputed.
 */
public final class RouteMetricsAggregator {

	private RouteMetricsAggregator() {

	}

	/**
	 * Merge the given figures, summing everything that shares a route id.
	 * @param metrics the partial figures, in any order
	 * @return one figure per route, ordered by request count descending
	 */
	public static List<RouteMetric> merge(Collection<RouteMetric> metrics) {
		Map<String, Accumulator> byRoute = new LinkedHashMap<>();
		for (RouteMetric metric : metrics) {
			byRoute.computeIfAbsent(metric.routeId(), (key) -> new Accumulator()).add(metric);
		}
		return byRoute.entrySet()
			.stream()
			.map((entry) -> entry.getValue().toMetric(entry.getKey()))
			.sorted(Comparator.comparingLong(RouteMetric::count).reversed())
			.toList();
	}

	/**
	 * Mutable per-route accumulator holding the totals the averages and rates are rebuilt
	 * from.
	 */
	private static final class Accumulator {

		private String uri;

		private long count;

		private double totalMs;

		private double maxMs;

		private long clientErrorCount;

		private long errorCount;

		private void add(RouteMetric metric) {
			if (this.uri == null) {
				this.uri = metric.uri();
			}
			this.count += metric.count();
			// Rebuild the total this average stood for, so a slow instance that served
			// few requests does not weigh as much as a fast one that served many.
			this.totalMs += metric.avgMs() * metric.count();
			this.maxMs = Math.max(this.maxMs, metric.maxMs());
			this.clientErrorCount += metric.clientErrorCount();
			this.errorCount += metric.errorCount();
		}

		private RouteMetric toMetric(String routeId) {
			double avgMs = (this.count > 0) ? this.totalMs / this.count : 0.0;
			double clientErrorRate = (this.count > 0) ? (double) this.clientErrorCount / this.count : 0.0;
			double errorRate = (this.count > 0) ? (double) this.errorCount / this.count : 0.0;
			return new RouteMetric(routeId, this.uri, this.count, avgMs, this.maxMs, this.clientErrorCount,
					clientErrorRate, this.errorCount, errorRate);
		}

	}

}
