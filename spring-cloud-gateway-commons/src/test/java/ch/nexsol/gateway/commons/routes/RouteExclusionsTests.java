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

package ch.nexsol.gateway.commons.routes;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RouteExclusions}.
 */
class RouteExclusionsTests {

	@Test
	void excludesARouteWhoseWholeIdMatches() {
		RouteExclusions exclusions = new RouteExclusions("metrics", List.of("docs", "internal-.*"));

		assertThat(exclusions.excludes("docs")).isTrue();
		assertThat(exclusions.excludes("internal-health")).isTrue();
	}

	@Test
	void keepsARouteThatOnlyStartsLikeAnExcludedOne() {
		RouteExclusions exclusions = new RouteExclusions("metrics", List.of("docs"));

		assertThat(exclusions.excludes("docs-public")).isFalse();
		assertThat(exclusions.excludes("orders")).isFalse();
	}

	@Test
	void excludesARouteWithoutAnId() {
		RouteExclusions exclusions = new RouteExclusions("metrics", List.of());

		assertThat(exclusions.excludes(null)).isTrue();
		assertThat(exclusions.excludes(" ")).isTrue();
	}

	@Test
	void keepsEveryNamedRouteWhenNothingIsExcluded() {
		assertThat(new RouteExclusions("metrics", List.of()).excludes("orders")).isFalse();
	}

	@Test
	void dropsAnExpressionThatDoesNotCompileAndKeepsTheOthers() {
		RouteExclusions exclusions = new RouteExclusions("service graph", List.of("docs(", "internal"));

		assertThat(exclusions.excludes("internal")).isTrue();
		assertThat(exclusions.excludes("docs(")).isFalse();
	}

}
