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

import java.util.List;

import ch.nexsol.gateway.ui.overview.OverviewContribution;
import ch.nexsol.gateway.ui.overview.OverviewStat;
import reactor.core.publisher.Flux;

/**
 * Contributes the traffic figures to the home page: the calls the gateway routed, the
 * latency they saw and the server errors they hit, aggregated over every route.
 */
public class MetricsOverviewContribution implements OverviewContribution {

	private final RouteMetricsService metricsService;

	/**
	 * Creates the contribution over the per-route metrics.
	 * @param metricsService the service aggregating the gateway request metrics
	 */
	public MetricsOverviewContribution(RouteMetricsService metricsService) {
		this.metricsService = metricsService;
	}

	@Override
	public Flux<OverviewStat> stats() {
		return Flux.defer(() -> Flux.fromIterable(toStats(this.metricsService.collect())));
	}

	/**
	 * Folds the per-route metrics into the three traffic figures shown on the home page.
	 * @param metrics the per-route metrics
	 * @return the contributed figures
	 */
	static List<OverviewStat> toStats(List<RouteMetric> metrics) {
		long calls = metrics.stream().mapToLong(RouteMetric::count).sum();
		long errors = metrics.stream().mapToLong(RouteMetric::errorCount).sum();
		double totalMs = metrics.stream().mapToDouble((metric) -> metric.avgMs() * metric.count()).sum();
		String latency = (calls > 0) ? Math.round(totalMs / calls) + " ms" : "—";
		String errorDetail = (calls > 0) ? Math.round(1000.0 * errors / calls) / 10.0 + "% of calls"
				: "no call recorded yet";
		return List.of(new OverviewStat("Calls", String.valueOf(calls), metrics.size() + " route(s) called", 20),
				new OverviewStat("Avg latency", latency, "weighted across every call", 30),
				new OverviewStat("Server errors", String.valueOf(errors), errorDetail, 40));
	}

}
