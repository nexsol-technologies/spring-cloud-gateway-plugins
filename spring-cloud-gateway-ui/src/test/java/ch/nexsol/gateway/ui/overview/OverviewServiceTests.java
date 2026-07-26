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

package ch.nexsol.gateway.ui.overview;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OverviewServiceTests {

	@Test
	void gathersEveryContributedFigureInOrder() {
		OverviewService service = serviceOver(() -> Flux.just(new OverviewStat("Late", "1", null, 50)),
				() -> Flux.just(new OverviewStat("Early", "2", "detail", 10)));

		StepVerifier.create(service.stats()).assertNext((stats) -> {
			assertThat(stats).extracting(OverviewStat::label).containsExactly("Early", "Late");
			assertThat(stats.get(0).detail()).isEqualTo("detail");
		}).verifyComplete();
	}

	@Test
	void reportsNoFigureWhenNothingContributesAny() {
		StepVerifier.create(serviceOver().stats()).assertNext((stats) -> assertThat(stats).isEmpty()).verifyComplete();
	}

	@Test
	void keepsTheOtherFiguresWhenOneContributionFails() {
		OverviewService service = serviceOver(() -> Flux.error(new IllegalStateException("registry gone")),
				() -> Flux.just(new OverviewStat("Routes", "3", null, 10)));

		StepVerifier.create(service.stats())
			.assertNext((stats) -> assertThat(stats).extracting(OverviewStat::label).containsExactly("Routes"))
			.verifyComplete();
	}

	@Test
	void readsTheUptimeOffTheApplicationContext() {
		ApplicationContext context = mock(ApplicationContext.class);
		when(context.getStartupDate()).thenReturn(System.currentTimeMillis() - Duration.ofMinutes(3).toMillis());
		OverviewService service = new OverviewService(emptyProvider(), context);

		assertThat(service.uptimeText()).startsWith("3m");
	}

	@Test
	void rendersAnUptimeWithItsTwoMostSignificantUnits() {
		assertThat(OverviewService.format(Duration.ofDays(3).plusHours(4).plusMinutes(9))).isEqualTo("3d 4h");
		assertThat(OverviewService.format(Duration.ofHours(2).plusMinutes(15).plusSeconds(9))).isEqualTo("2h 15m");
		assertThat(OverviewService.format(Duration.ofMinutes(12).plusSeconds(5))).isEqualTo("12m 5s");
		assertThat(OverviewService.format(Duration.ofSeconds(9))).isEqualTo("9s");
	}

	@Test
	void rendersAnUptimeOfZeroRatherThanANegativeOne() {
		assertThat(OverviewService.format(Duration.ofSeconds(-5))).isEqualTo("0s");
	}

	private static OverviewService serviceOver(OverviewContribution... contributions) {
		@SuppressWarnings("unchecked")
		ObjectProvider<OverviewContribution> provider = mock(ObjectProvider.class);
		when(provider.orderedStream()).thenAnswer((invocation) -> Stream.of(contributions));
		return new OverviewService(provider, mock(ApplicationContext.class));
	}

	@SuppressWarnings("unchecked")
	private static ObjectProvider<OverviewContribution> emptyProvider() {
		ObjectProvider<OverviewContribution> provider = mock(ObjectProvider.class);
		when(provider.orderedStream()).thenAnswer((invocation) -> Stream.empty());
		return provider;
	}

}
