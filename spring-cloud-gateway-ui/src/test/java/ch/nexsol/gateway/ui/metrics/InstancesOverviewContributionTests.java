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

import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetric.InstanceInstrumentation;
import ch.nexsol.gateway.metrics.InstanceMetric.JvmStats;
import ch.nexsol.gateway.metrics.InstanceMetric.NettyStats;
import ch.nexsol.gateway.metrics.InstanceMetric.SystemStats;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.ui.overview.OverviewStat;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link InstancesOverviewContribution}.
 */
class InstancesOverviewContributionTests {

	@SuppressWarnings("unchecked")
	private InstancesOverviewContribution contributionOver(InstanceMetricsSource source) {
		ObjectProvider<InstanceMetricsSource> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(source);
		return new InstancesOverviewContribution(provider);
	}

	private static InstanceMetric instance(String id) {
		return new InstanceMetric(id, null, 300, new JvmStats(1, 2, 3, 0.0, 0.0, 0, 4, 5, 6),
				new SystemStats(0.1, 0.2, 1.0, 8, 10, 100), new NettyStats(0, 4), List.of(),
				new InstanceInstrumentation(false, false));
	}

	@Test
	void contributesTheInstanceCountWithItsCoverage() {
		InstanceMetricsSource source = () -> Mono.just(new InstanceMetricsSnapshot("3 instances, consolidated",
				List.of(instance("a"), instance("b"), instance("c"))));

		StepVerifier.create(contributionOver(source).stats()).assertNext((stat) -> {
			assertThat(stat.label()).isEqualTo("Instances");
			assertThat(stat.value()).isEqualTo("3");
			// The coverage rides along with the count: three instances reached out of
			// five is a different statement from three instances running.
			assertThat(stat.detail()).isEqualTo("3 instances, consolidated");
		}).verifyComplete();
	}

	@Test
	void contributesNothingWhenNoSourceIsAvailable() {
		StepVerifier.create(contributionOver(null).stats()).verifyComplete();
	}

	@Test
	void reportsZeroRatherThanNothingWhenNoInstanceAnswered() {
		InstanceMetricsSource source = () -> Mono.just(InstanceMetricsSnapshot.empty("Redis unavailable"));

		StepVerifier.create(contributionOver(source).stats())
			.assertNext((stat) -> assertThat(stat).extracting(OverviewStat::value, OverviewStat::detail)
				.containsExactly("0", "Redis unavailable"))
			.verifyComplete();
	}

}
