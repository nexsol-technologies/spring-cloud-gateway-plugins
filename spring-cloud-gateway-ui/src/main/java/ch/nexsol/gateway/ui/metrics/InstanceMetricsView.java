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
import java.util.Map;

import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;

/**
 * What the instances view is served: an {@link InstanceMetricsSnapshot} and the routes
 * behind the downstream addresses its connection pools report.
 * <p>
 * The route names travel beside the rows rather than inside them: they are resolved from
 * the route table and the service registry, which describe the cluster, so the same
 * address means the same routes on every instance and nothing is repeated per row.
 *
 * @param coverage what the rows cover, ready to be shown to a human
 * @param instances the figures, one row per instance
 * @param routesByAddress the route ids per {@code host:port}, an address that could not
 * be named being absent. The list is served whole and shortened by the view: a contract
 * generating one route per operation puts twenty of them on a single address, which is a
 * fact about the gateway rather than something to hide here
 */
public record InstanceMetricsView(String coverage, List<InstanceMetric> instances,
		Map<String, List<String>> routesByAddress) {
}
