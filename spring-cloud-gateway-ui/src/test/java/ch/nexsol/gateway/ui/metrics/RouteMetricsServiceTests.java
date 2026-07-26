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
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouteMetricsServiceTests {

	@SuppressWarnings("unchecked")
	private RouteMetricsService serviceFor(MeterRegistry registry) {
		ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(registry);
		return new RouteMetricsService(provider);
	}

	@Test
	void aggregatesTimersOfTheSameRouteAndComputesErrorRate() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Timer ok = Timer.builder(RouteMetricsService.REQUESTS_METER)
			.tags("routeId", "alpha", "routeUri", "http://alpha", "httpStatusCode", "200")
			.register(registry);
		ok.record(100, TimeUnit.MILLISECONDS);
		ok.record(200, TimeUnit.MILLISECONDS);
		Timer failed = Timer.builder(RouteMetricsService.REQUESTS_METER)
			.tags("routeId", "alpha", "routeUri", "http://alpha", "httpStatusCode", "500")
			.register(registry);
		failed.record(300, TimeUnit.MILLISECONDS);

		List<RouteMetric> metrics = serviceFor(registry).collect();

		assertThat(metrics).hasSize(1);
		RouteMetric alpha = metrics.get(0);
		assertThat(alpha.routeId()).isEqualTo("alpha");
		assertThat(alpha.uri()).isEqualTo("http://alpha");
		assertThat(alpha.count()).isEqualTo(3);
		assertThat(alpha.avgMs()).isCloseTo(200.0, offset(1.0));
		assertThat(alpha.maxMs()).isCloseTo(300.0, offset(1.0));
		assertThat(alpha.errorCount()).isEqualTo(1);
		assertThat(alpha.errorRate()).isCloseTo(1.0 / 3.0, offset(0.001));
	}

	@Test
	void ordersRoutesByCallCountDescending() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Timer.builder(RouteMetricsService.REQUESTS_METER)
			.tags("routeId", "low", "httpStatusCode", "200")
			.register(registry)
			.record(10, TimeUnit.MILLISECONDS);
		Timer busy = Timer.builder(RouteMetricsService.REQUESTS_METER)
			.tags("routeId", "busy", "httpStatusCode", "200")
			.register(registry);
		busy.record(10, TimeUnit.MILLISECONDS);
		busy.record(10, TimeUnit.MILLISECONDS);

		List<RouteMetric> metrics = serviceFor(registry).collect();

		assertThat(metrics).extracting(RouteMetric::routeId).containsExactly("busy", "low");
	}

	@Test
	void returnsEmptyWhenNoRegistryIsAvailable() {
		assertThat(serviceFor(null).collect()).isEmpty();
	}

}
