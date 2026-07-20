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

package ch.nexsol.gateway.routes.files;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link FileRouteDefinitionLoader} and {@link RouteDefinitionFileParser}
 * covering YAML and JSON parsing, shorthand and object element forms, and error cases.
 */
class FileRouteDefinitionLoaderTests {

	private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

	private FileRouteDefinitionLoader loader(String... locations) {
		return new FileRouteDefinitionLoader(new RouteDefinitionFileParser(), List.of(locations), this.resolver);
	}

	@Test
	void parsesYamlRouteWithShorthandAndObjectElements() {
		List<RouteDefinition> definitions = loader("classpath:routes/sample-routes.yaml").load();

		assertThat(definitions).hasSize(1);
		RouteDefinition route = definitions.get(0);
		assertThat(route.getId()).isEqualTo("after_route");
		assertThat(route.getUri()).hasToString("https://example.org");
		assertThat(route.getOrder()).isEqualTo(1);
		assertThat(route.getMetadata()).containsEntry("tier", "gold");

		assertThat(route.getPredicates()).hasSize(2);
		// shorthand "Cookie=mycookie,mycookievalue" parsed via the native SCG constructor
		assertThat(route.getPredicates().get(0).getName()).isEqualTo("Cookie");
		assertThat(route.getPredicates().get(0).getArgs()).hasSize(2);
		// object form
		assertThat(route.getPredicates().get(1).getName()).isEqualTo("Path");
		assertThat(route.getPredicates().get(1).getArgs()).containsEntry("pattern", "/api/**");

		assertThat(route.getFilters()).hasSize(2);
		assertThat(route.getFilters().get(0).getName()).isEqualTo("AddRequestHeader");
		assertThat(route.getFilters().get(1).getName()).isEqualTo("Retry");
		assertThat(route.getFilters().get(1).getArgs()).containsEntry("retries", "3");
	}

	@Test
	void parsesJsonTopLevelArray() {
		List<RouteDefinition> definitions = loader("classpath:routes/sample-routes.json").load();

		assertThat(definitions).hasSize(1);
		RouteDefinition route = definitions.get(0);
		assertThat(route.getId()).isEqualTo("json_route");
		assertThat(route.getPredicates().get(0).getName()).isEqualTo("Method");
	}

	@Test
	void aggregatesMultipleLocations() {
		List<RouteDefinition> definitions = loader("classpath:routes/sample-routes.yaml",
				"classpath:routes/sample-routes.json")
			.load();

		assertThat(definitions).extracting(RouteDefinition::getId)
			.containsExactlyInAnyOrder("after_route", "json_route");
	}

	@Test
	void failsOnRouteMissingUri() {
		assertThatExceptionOfType(RouteDefinitionFileException.class)
			.isThrownBy(() -> loader("classpath:routes-invalid/missing-uri.yaml").load())
			.withMessageContaining("missing a 'uri'");
	}

}
