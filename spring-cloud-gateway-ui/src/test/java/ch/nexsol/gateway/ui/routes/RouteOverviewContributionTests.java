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

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteOverviewContributionTests {

	@Test
	void countsTheRoutesOfEachSource() {
		List<RouteView> routes = List.of(route("alpha", "Properties"), route("bravo", "Properties"),
				route("charlie", "Database"));

		assertThat(RouteOverviewContribution.breakdown(routes)).isEqualTo("2 Properties, 1 Database");
	}

	@Test
	void saysSoWhenNoSourceDeclaredAnyRoute() {
		assertThat(RouteOverviewContribution.breakdown(List.of())).isEqualTo("no route declared in any source");
	}

	private static RouteView route(String id, String source) {
		return new RouteView(id, "http://" + id, 0, List.of(), List.of(), source, false);
	}

}
