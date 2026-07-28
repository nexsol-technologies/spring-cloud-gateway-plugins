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
import ch.nexsol.gateway.ui.overview.OverviewStat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsOverviewContributionTests {

	private static final String COVERAGE = "this instance only (gateway-1)";

	@Test
	void weightsTheLatencyByTheCallsEachRouteTook() {
		// 10 calls at 100 ms and 90 calls at 10 ms average out at 19 ms, not at 55 ms.
		List<OverviewStat> stats = MetricsOverviewContribution
			.toStats(List.of(new RouteMetric("slow", "http://slow", 10, 100.0, 150.0, 0, 0.0, 0, 0.0),
					new RouteMetric("fast", "http://fast", 90, 10.0, 20.0, 5, 0.055, 9, 0.1)), COVERAGE);

		assertThat(stats).extracting(OverviewStat::label)
			.containsExactly("Calls", "Avg latency", "Client errors", "Server errors");
		assertThat(stats.get(0).value()).isEqualTo("100");
		assertThat(stats.get(0).detail()).isEqualTo("2 route(s) called — " + COVERAGE);
		assertThat(stats.get(1).value()).isEqualTo("19 ms");
		assertThat(stats.get(3).value()).isEqualTo("9");
		assertThat(stats.get(3).detail()).isEqualTo("9.0% of calls");
	}

	@Test
	void reportsTheClientErrorsApartFromTheServerOnes() {
		List<OverviewStat> stats = MetricsOverviewContribution
			.toStats(List.of(new RouteMetric("orders", "http://orders", 100, 10.0, 20.0, 25, 0.25, 4, 0.04)), COVERAGE);

		OverviewStat clientErrors = stats.get(2);
		assertThat(clientErrors.label()).isEqualTo("Client errors");
		assertThat(clientErrors.value()).isEqualTo("25");
		assertThat(clientErrors.detail()).isEqualTo("25.0% of calls");
		assertThat(stats.get(3).value()).isEqualTo("4");
	}

	@Test
	void reportsNoLatencyBeforeAnyCallIsRecorded() {
		List<OverviewStat> stats = MetricsOverviewContribution.toStats(List.of(), COVERAGE);

		assertThat(stats.get(0).value()).isEqualTo("0");
		assertThat(stats.get(1).value()).isEqualTo("—");
		assertThat(stats.get(2).detail()).isEqualTo("no call recorded yet");
		assertThat(stats.get(3).detail()).isEqualTo("no call recorded yet");
	}

}
