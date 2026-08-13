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

package ch.nexsol.gateway.metrics.discovery;

import java.util.List;

import ch.nexsol.gateway.metrics.LocalRouteMetricsSource;
import ch.nexsol.gateway.metrics.RouteMetric;
import ch.nexsol.gateway.metrics.RouteMetricsAggregator;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the figures of <em>this</em> instance to the sibling instances.
 * <p>
 * It is deliberately bound to the local source rather than to whichever
 * {@code RouteMetricsSource} is active: were it to serve the consolidated figures, an
 * instance polling its siblings would poll itself through them, and the fan-out would
 * never terminate.
 */
@RestController
public class LocalRouteMetricsController {

	private final LocalRouteMetricsSource localSource;

	/**
	 * Creates the endpoint over the local source.
	 * @param localSource the source reading this instance's meter registry
	 */
	public LocalRouteMetricsController(LocalRouteMetricsSource localSource) {
		this.localSource = localSource;
	}

	/**
	 * Returns the figures held by this instance.
	 * @return one figure per route, for this instance only
	 */
	// Bound to the configured path rather than to the constant: the fan-out polls
	// `discovery.path`, so moving it would otherwise leave every sibling polling a path
	// no instance serves, and reporting them all as unreachable.
	@GetMapping("${spring.cloud.gateway.server.webflux.metrics.discovery.path:"
			+ DiscoveryMetricsProperties.DEFAULT_PATH + "}")
	public List<RouteMetric> local() {
		return RouteMetricsAggregator.merge(this.localSource.read());
	}

}
