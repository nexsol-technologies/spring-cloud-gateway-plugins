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

	private RouteMetricsService serviceFor(MeterRegistry registry) {
		return serviceFor(registry, new RouteMetricsProperties());
	}

	@SuppressWarnings("unchecked")
	private RouteMetricsService serviceFor(MeterRegistry registry, RouteMetricsProperties properties) {
		ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(registry);
		return new RouteMetricsService(provider, properties);
	}

	private static SimpleMeterRegistry registryWith(String... routeIds) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		for (String routeId : routeIds) {
			Timer.builder(RouteMetricsService.REQUESTS_METER)
				.tags("routeId", routeId, "httpStatusCode", "200")
				.register(registry)
				.record(10, TimeUnit.MILLISECONDS);
		}
		return registry;
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

	@Test
	void countsClientErrorsApartFromServerErrors() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		for (String status : List.of("200", "403", "404", "500")) {
			Timer.builder(RouteMetricsService.REQUESTS_METER)
				.tags("routeId", "orders", "httpStatusCode", status)
				.register(registry)
				.record(10, TimeUnit.MILLISECONDS);
		}

		RouteMetric orders = serviceFor(registry).collect().get(0);

		assertThat(orders.count()).isEqualTo(4);
		// 403 and 404 are the caller being turned away, not the backend failing.
		assertThat(orders.clientErrorCount()).isEqualTo(2);
		assertThat(orders.clientErrorRate()).isCloseTo(0.5, offset(0.001));
		assertThat(orders.errorCount()).isEqualTo(1);
		assertThat(orders.errorRate()).isCloseTo(0.25, offset(0.001));
	}

	@Test
	void excludesTheOpenapiDocumentationRoutesByDefault() {
		SimpleMeterRegistry registry = registryWith("orders", "openapi-docs-discovery-petstore",
				"openapi-docs-discovery-SERVICE-A");

		List<RouteMetric> metrics = serviceFor(registry).collect();

		assertThat(metrics).extracting(RouteMetric::routeId).containsExactly("orders");
	}

	@Test
	void excludesEveryRouteMatchingAConfiguredExpression() {
		RouteMetricsProperties properties = new RouteMetricsProperties();
		properties.setExcludedRoutes(List.of("internal_.*", ".*_healthcheck"));
		SimpleMeterRegistry registry = registryWith("orders", "internal_metrics", "billing_healthcheck");

		List<RouteMetric> metrics = serviceFor(registry, properties).collect();

		assertThat(metrics).extracting(RouteMetric::routeId).containsExactly("orders");
	}

	@Test
	void matchesTheWholeRouteIdRatherThanAFragment() {
		RouteMetricsProperties properties = new RouteMetricsProperties();
		properties.setExcludedRoutes(List.of("docs"));
		SimpleMeterRegistry registry = registryWith("docs", "docs-public");

		List<RouteMetric> metrics = serviceFor(registry, properties).collect();

		assertThat(metrics).extracting(RouteMetric::routeId).containsExactly("docs-public");
	}

	@Test
	void showsEveryRouteWhenTheExclusionsAreCleared() {
		RouteMetricsProperties properties = new RouteMetricsProperties();
		properties.setExcludedRoutes(List.of());
		SimpleMeterRegistry registry = registryWith("orders", "openapi-docs-discovery-petstore");

		List<RouteMetric> metrics = serviceFor(registry, properties).collect();

		assertThat(metrics).extracting(RouteMetric::routeId)
			.containsExactlyInAnyOrder("orders", "openapi-docs-discovery-petstore");
	}

	@Test
	void keepsTheUsableExpressionsWhenOneIsMalformed() {
		RouteMetricsProperties properties = new RouteMetricsProperties();
		properties.setExcludedRoutes(List.of("[unclosed", "internal_.*"));
		SimpleMeterRegistry registry = registryWith("orders", "internal_metrics");

		List<RouteMetric> metrics = serviceFor(registry, properties).collect();

		assertThat(metrics).extracting(RouteMetric::routeId).containsExactly("orders");
	}

}
