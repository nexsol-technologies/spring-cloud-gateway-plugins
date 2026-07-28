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

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests the folding of the partial figures reported by several timers or several
 * instances.
 */
class RouteMetricsAggregatorTests {

	@Test
	void sumsTheCountsOfTheSameRoute() {
		List<RouteMetric> merged = RouteMetricsAggregator
			.merge(List.of(metric("orders", 100, 10.0), metric("orders", 300, 10.0)));

		assertThat(merged).singleElement().satisfies((metric) -> {
			assertThat(metric.routeId()).isEqualTo("orders");
			assertThat(metric.count()).isEqualTo(400);
		});
	}

	@Test
	void weightsTheLatencyByTheNumberOfRequests() {
		// One instance served 100 calls at 10 ms, another 300 calls at 50 ms: the average
		// is 40 ms, not the 30 ms an average of averages would give.
		List<RouteMetric> merged = RouteMetricsAggregator
			.merge(List.of(metric("orders", 100, 10.0), metric("orders", 300, 50.0)));

		assertThat(merged).singleElement()
			.satisfies((metric) -> assertThat(metric.avgMs()).isCloseTo(40.0, within(0.001)));
	}

	@Test
	void keepsTheSlowestRequestAcrossTheParts() {
		List<RouteMetric> merged = RouteMetricsAggregator
			.merge(List.of(withMax("orders", 120.0), withMax("orders", 900.0)));

		assertThat(merged).singleElement().satisfies((metric) -> assertThat(metric.maxMs()).isEqualTo(900.0));
	}

	@Test
	void recomputesTheErrorRatesOverTheMergedTotal() {
		RouteMetric healthy = new RouteMetric("orders", "http://orders", 900, 10.0, 20.0, 0, 0.0, 0, 0.0);
		RouteMetric failing = new RouteMetric("orders", "http://orders", 100, 10.0, 20.0, 10, 0.1, 90, 0.9);

		List<RouteMetric> merged = RouteMetricsAggregator.merge(List.of(healthy, failing));

		assertThat(merged).singleElement().satisfies((metric) -> {
			assertThat(metric.errorCount()).isEqualTo(90);
			assertThat(metric.errorRate()).isCloseTo(0.09, within(0.0001));
			assertThat(metric.clientErrorRate()).isCloseTo(0.01, within(0.0001));
		});
	}

	@Test
	void keepsRoutesApartAndOrdersThemByCount() {
		List<RouteMetric> merged = RouteMetricsAggregator
			.merge(List.of(metric("quiet", 5, 10.0), metric("busy", 500, 10.0), metric("quiet", 5, 10.0)));

		assertThat(merged).extracting(RouteMetric::routeId).containsExactly("busy", "quiet");
	}

	@Test
	void keepsTheFirstKnownTargetUri() {
		RouteMetric withoutUri = new RouteMetric("orders", null, 10, 1.0, 1.0, 0, 0.0, 0, 0.0);
		RouteMetric withUri = new RouteMetric("orders", "http://orders", 10, 1.0, 1.0, 0, 0.0, 0, 0.0);

		assertThat(RouteMetricsAggregator.merge(List.of(withUri, withoutUri))).singleElement()
			.satisfies((metric) -> assertThat(metric.uri()).isEqualTo("http://orders"));
	}

	@Test
	void reportsNothingForNoInput() {
		assertThat(RouteMetricsAggregator.merge(List.of())).isEmpty();
	}

	@Test
	void leavesARouteWithoutTrafficAtZeroRatherThanDividingByZero() {
		assertThat(RouteMetricsAggregator.merge(List.of(metric("idle", 0, 0.0)))).singleElement()
			.satisfies((metric) -> {
				assertThat(metric.avgMs()).isZero();
				assertThat(metric.errorRate()).isZero();
			});
	}

	private static RouteMetric metric(String routeId, long count, double avgMs) {
		return new RouteMetric(routeId, "http://" + routeId, count, avgMs, avgMs, 0, 0.0, 0, 0.0);
	}

	private static RouteMetric withMax(String routeId, double maxMs) {
		return new RouteMetric(routeId, "http://" + routeId, 10, 10.0, maxMs, 0, 0.0, 0, 0.0);
	}

}
