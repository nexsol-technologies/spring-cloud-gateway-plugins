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

import ch.nexsol.gateway.ui.overview.OverviewStat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsOverviewContributionTests {

	@Test
	void weightsTheLatencyByTheCallsEachRouteTook() {
		// 10 calls at 100 ms and 90 calls at 10 ms average out at 19 ms, not at 55 ms.
		List<OverviewStat> stats = MetricsOverviewContribution
			.toStats(List.of(new RouteMetric("slow", "http://slow", 10, 100.0, 150.0, 0, 0.0),
					new RouteMetric("fast", "http://fast", 90, 10.0, 20.0, 9, 0.1)));

		assertThat(stats).extracting(OverviewStat::label).containsExactly("Calls", "Avg latency", "Server errors");
		assertThat(stats.get(0).value()).isEqualTo("100");
		assertThat(stats.get(0).detail()).isEqualTo("2 route(s) called");
		assertThat(stats.get(1).value()).isEqualTo("19 ms");
		assertThat(stats.get(2).value()).isEqualTo("9");
		assertThat(stats.get(2).detail()).isEqualTo("9.0% of calls");
	}

	@Test
	void reportsNoLatencyBeforeAnyCallIsRecorded() {
		List<OverviewStat> stats = MetricsOverviewContribution.toStats(List.of());

		assertThat(stats.get(0).value()).isEqualTo("0");
		assertThat(stats.get(1).value()).isEqualTo("—");
		assertThat(stats.get(2).detail()).isEqualTo("no call recorded yet");
	}

}
