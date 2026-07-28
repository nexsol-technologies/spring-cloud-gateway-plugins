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

import ch.nexsol.gateway.metrics.RouteMetric;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import ch.nexsol.gateway.ui.overview.OverviewContribution;
import ch.nexsol.gateway.ui.overview.OverviewStat;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Contributes the traffic figures to the home page: the calls the gateway routed, the
 * latency they saw and the server errors they hit, aggregated over every route.
 */
public class MetricsOverviewContribution implements OverviewContribution {

	private final ObjectProvider<RouteMetricsSource> metricsSource;

	/**
	 * Creates the contribution over the (optional) active metrics source.
	 * @param metricsSource the provider over the source the per-route figures are read
	 * from
	 */
	public MetricsOverviewContribution(ObjectProvider<RouteMetricsSource> metricsSource) {
		this.metricsSource = metricsSource;
	}

	@Override
	public Flux<OverviewStat> stats() {
		return Flux.defer(() -> {
			RouteMetricsSource source = this.metricsSource.getIfAvailable();
			if (source == null) {
				return Flux.empty();
			}
			return source.collect()
				.flatMapMany((snapshot) -> Flux.fromIterable(toStats(snapshot.metrics(), snapshot.coverage())));
		});
	}

	/**
	 * Folds the per-route metrics into the traffic figures shown on the home page. The
	 * coverage rides along with the call count: a number that only covers one instance
	 * must say so where it is read, not in the documentation.
	 * @param metrics the per-route metrics
	 * @param coverage what those metrics cover
	 * @return the contributed figures
	 */
	static List<OverviewStat> toStats(List<RouteMetric> metrics, String coverage) {
		long calls = metrics.stream().mapToLong(RouteMetric::count).sum();
		long clientErrors = metrics.stream().mapToLong(RouteMetric::clientErrorCount).sum();
		long errors = metrics.stream().mapToLong(RouteMetric::errorCount).sum();
		double totalMs = metrics.stream().mapToDouble((metric) -> metric.avgMs() * metric.count()).sum();
		String latency = (calls > 0) ? Math.round(totalMs / calls) + " ms" : "—";
		return List.of(
				new OverviewStat("Calls", String.valueOf(calls), metrics.size() + " route(s) called — " + coverage, 20),
				new OverviewStat("Avg latency", latency, "weighted across every call", 30),
				new OverviewStat("Client errors", String.valueOf(clientErrors), shareOfCalls(clientErrors, calls), 35),
				new OverviewStat("Server errors", String.valueOf(errors), shareOfCalls(errors, calls), 40));
	}

	private static String shareOfCalls(long responses, long calls) {
		return (calls > 0) ? Math.round(1000.0 * responses / calls) / 10.0 + "% of calls" : "no call recorded yet";
	}

}
