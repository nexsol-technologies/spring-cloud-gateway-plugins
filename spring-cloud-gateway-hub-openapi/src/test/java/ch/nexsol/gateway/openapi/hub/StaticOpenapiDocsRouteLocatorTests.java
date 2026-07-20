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

package ch.nexsol.gateway.openapi.hub;

import java.net.URI;
import java.util.List;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties;
import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.FilterDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link StaticOpenapiDocsRouteLocator}, verifying the OpenAPI documentation
 * routes it emits for the configured OpenAPI sources.
 */
class StaticOpenapiDocsRouteLocatorTests {

	@SuppressWarnings("unchecked")
	private StaticOpenapiDocsRouteLocator locatorFor(RoutesOpenapiProperties properties) {
		ObjectProvider<RoutesOpenapiProperties> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(properties);
		return new StaticOpenapiDocsRouteLocator(provider);
	}

	@Test
	void emitsNothingWhenPropertiesAbsent() {
		StepVerifier.create(locatorFor(null).getRouteDefinitions()).verifyComplete();
	}

	@Test
	void emitsDocumentationRoutePerSource() {
		RoutesOpenapiProperties properties = new RoutesOpenapiProperties();
		Source source = new Source();
		source.setId("petstore");
		source.setUri(URI.create("https://petstore3.swagger.io"));
		source.setSpecUrl("https://petstore3.swagger.io/api/v3/openapi.json");
		properties.setSources(List.of(source));

		StepVerifier.create(locatorFor(properties).getRouteDefinitions()).assertNext((route) -> {
			assertThat(route.getId()).isEqualTo("openapi-docs-discovery-petstore");
			assertThat(route.getUri()).hasToString("https://petstore3.swagger.io");
			assertThat(route.getMetadata()).containsEntry("name", "petstore");
			assertThat(route.getPredicates()).singleElement()
				.satisfies((predicate) -> assertThat(predicate.getName()).isEqualTo("Path"));
			assertThat(route.getFilters()).extracting(FilterDefinition::getName)
				.containsExactly("RewritePath", "OpenapiModifyResponseBody");
			// RewritePath maps the hub docs path to the upstream spec path.
			assertThat(route.getFilters().get(0).getArgs().values()).contains("/v3/api-docs/petstore",
					"/api/v3/openapi.json");
		}).verifyComplete();
	}

	@Test
	void skipsSourcesMissingIdOrSpecUrl() {
		RoutesOpenapiProperties properties = new RoutesOpenapiProperties();
		Source incomplete = new Source();
		incomplete.setId("no-spec");
		properties.setSources(List.of(incomplete));

		StepVerifier.create(locatorFor(properties).getRouteDefinitions()).verifyComplete();
	}

}
