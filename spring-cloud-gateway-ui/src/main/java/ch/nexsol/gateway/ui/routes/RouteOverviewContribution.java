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

package ch.nexsol.gateway.ui.routes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ch.nexsol.gateway.ui.overview.OverviewContribution;
import ch.nexsol.gateway.ui.overview.OverviewStat;
import reactor.core.publisher.Flux;

/**
 * Contributes the route figures to the home page: how many routes the gateway resolves,
 * and how they break down across the sources they were read from.
 */
public class RouteOverviewContribution implements OverviewContribution {

	private final RouteInventoryService inventoryService;

	/**
	 * Creates the contribution over the route inventory.
	 * @param inventoryService the service listing the resolved route definitions
	 */
	public RouteOverviewContribution(RouteInventoryService inventoryService) {
		this.inventoryService = inventoryService;
	}

	@Override
	public Flux<OverviewStat> stats() {
		return this.inventoryService.routes()
			.flatMapMany((routes) -> Flux
				.just(new OverviewStat("Routes", String.valueOf(routes.size()), breakdown(routes), 10)));
	}

	/**
	 * Renders the per-source route counts as a single line, e.g.
	 * {@code 4 Properties, 2 Database}.
	 * @param routes the resolved routes
	 * @return the breakdown, or a hint when there is no route at all
	 */
	static String breakdown(List<RouteView> routes) {
		if (routes.isEmpty()) {
			return "no route declared in any source";
		}
		Map<String, Integer> perSource = new LinkedHashMap<>();
		for (RouteView route : routes) {
			perSource.merge(route.source(), 1, (left, right) -> left + right);
		}
		return perSource.entrySet()
			.stream()
			.map((source) -> source.getValue() + " " + source.getKey())
			.collect(Collectors.joining(", "));
	}

}
