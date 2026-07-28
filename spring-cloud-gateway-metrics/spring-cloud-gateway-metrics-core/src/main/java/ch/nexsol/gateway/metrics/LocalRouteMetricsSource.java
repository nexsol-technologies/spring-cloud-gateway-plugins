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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads the per-request timer the gateway publishes
 * ({@code spring.cloud.gateway.requests}) from the meter registry of the running
 * instance.
 * <p>
 * This is the figure of one JVM. Behind a load balancer it is a share of the traffic, not
 * the traffic &mdash; which is why the snapshot is labelled with the instance it came
 * from, and why the provider modules exist. The {@link MeterRegistry} is resolved lazily
 * and optionally: when none is present the source reports no data instead of failing.
 */
public class LocalRouteMetricsSource implements RouteMetricsSource {

	/** Name of the timer published by the gateway metrics filter for each request. */
	public static final String REQUESTS_METER = "spring.cloud.gateway.requests";

	private final ObjectProvider<MeterRegistry> meterRegistry;

	private final RouteExclusions excludedRoutes;

	private final String coverage;

	/**
	 * Creates the source reading from the (optional) meter registry.
	 * @param meterRegistry the provider over the application meter registry
	 * @param properties the metrics configuration, holding the route exclusions
	 * @param identity the identity of the running instance
	 */
	public LocalRouteMetricsSource(ObjectProvider<MeterRegistry> meterRegistry, MetricsProperties properties,
			InstanceIdentity identity) {
		this.meterRegistry = meterRegistry;
		this.excludedRoutes = new RouteExclusions(properties.getExcludedRoutes());
		this.coverage = "this instance only (" + identity.id() + ")";
	}

	@Override
	public Mono<RouteMetricsSnapshot> collect() {
		return Mono.fromSupplier(() -> new RouteMetricsSnapshot(this.coverage, RouteMetricsAggregator.merge(read())));
	}

	/**
	 * Reads the timers of this instance as one partial figure per timer, left to be
	 * merged per route. Exposed so a provider consolidating several instances can reuse
	 * the local reading as its own contribution.
	 * @return the partial figures held by this instance
	 */
	public List<RouteMetric> read() {
		MeterRegistry registry = this.meterRegistry.getIfAvailable();
		if (registry == null) {
			return List.of();
		}
		List<RouteMetric> metrics = new ArrayList<>();
		for (Timer timer : registry.find(REQUESTS_METER).timers()) {
			String routeId = timer.getId().getTag("routeId");
			if (this.excludedRoutes.excludes(routeId)) {
				continue;
			}
			long count = timer.count();
			String status = timer.getId().getTag("httpStatusCode");
			long clientErrors = isStatusClass(status, '4') ? count : 0;
			long serverErrors = isStatusClass(status, '5') ? count : 0;
			double totalMs = timer.totalTime(TimeUnit.MILLISECONDS);
			metrics.add(new RouteMetric(routeId, timer.getId().getTag("routeUri"), count,
					(count > 0) ? totalMs / count : 0.0, timer.max(TimeUnit.MILLISECONDS), clientErrors,
					(count > 0) ? (double) clientErrors / count : 0.0, serverErrors,
					(count > 0) ? (double) serverErrors / count : 0.0));
		}
		return metrics;
	}

	private static boolean isStatusClass(String httpStatusCode, char leadingDigit) {
		return httpStatusCode != null && httpStatusCode.length() == 3 && httpStatusCode.charAt(0) == leadingDigit;
	}

}
