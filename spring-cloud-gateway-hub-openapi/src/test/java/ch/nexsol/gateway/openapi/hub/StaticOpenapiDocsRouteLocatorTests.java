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

import ch.nexsol.gateway.routes.openapi.OpenapiSourcesLoader;
import ch.nexsol.gateway.routes.openapi.RouteGenerationMode;
import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties;
import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

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
		ObjectProvider<OpenapiSourcesLoader> provider = mock(ObjectProvider.class);
		OpenapiSourcesLoader loader = (properties != null)
				? new OpenapiSourcesLoader(properties, new PathMatchingResourcePatternResolver()) : null;
		when(provider.getIfAvailable()).thenReturn(loader);
		return new StaticOpenapiDocsRouteLocator(provider);
	}

	@Test
	void emitsNothingWhenTheRouteGeneratorIsAbsent() {
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
			// RemoveRequestHeader strips the cookies of the browser: a contract is read
			// with none, and a service handed a session id it cannot resolve answers by
			// telling the browser to drop the cookie -- which on this origin is the one
			// the console signed the operator in with.
			assertThat(route.getFilters()).extracting(FilterDefinition::getName)
				.containsExactly("RewritePath", "OpenapiModifyResponseBody", "RemoveRequestHeader");
			assertThat(route.getFilters().get(2).getArgs().values()).containsExactly("Cookie");
			// RewritePath maps the hub docs path to the upstream spec path.
			assertThat(route.getFilters().get(0).getArgs().values()).contains("/v3/api-docs/petstore",
					"/api/v3/openapi.json");
		}).verifyComplete();
	}

	/**
	 * A source declared for its contract alone generates no route, and that is exactly
	 * what it is here for: its routes exist elsewhere, and only the contract is missing
	 * from the hub. The documentation route is what makes it appear, so it must be
	 * emitted just the same.
	 */
	@Test
	void emitsTheDocumentationRouteOfASourceThatGeneratesNoRoute() {
		RoutesOpenapiProperties properties = new RoutesOpenapiProperties();
		Source source = new Source();
		source.setId("alert-service");
		source.setSpecUrl("https://alert-service.example.ch/v3/api-docs");
		source.setMode(RouteGenerationMode.NO_ROUTE);
		properties.setSources(List.of(source));

		StepVerifier.create(locatorFor(properties).getRouteDefinitions()).assertNext((route) -> {
			assertThat(route.getId()).isEqualTo("openapi-docs-discovery-alert-service");
			assertThat(route.getMetadata()).containsEntry("name", "alert-service");
			// The document is proxied from where it lives, not from the 'uri' of the
			// routes: there are none, and nothing here required one to be declared.
			assertThat(route.getUri()).hasToString("https://alert-service.example.ch");
		}).verifyComplete();
	}

	@Test
	void emitsDocumentationRouteForSourcesDeclaredInADocument() {
		// Sources configured through 'sources-locations' generate routes, so they must
		// reach the aggregated Swagger UI exactly like the inline ones.
		RoutesOpenapiProperties properties = new RoutesOpenapiProperties();
		properties.setSourcesLocations(List.of("classpath:openapi/hub-sources.yaml"));

		StepVerifier.create(locatorFor(properties).getRouteDefinitions()).assertNext((route) -> {
			assertThat(route.getId()).isEqualTo("openapi-docs-discovery-from-document");
			assertThat(route.getMetadata()).containsEntry("name", "from-document");
		}).verifyComplete();
	}

	@Test
	void advertisesThePathPrefixSoTheConsoleCallsTheGeneratedRoutes() {
		// The generated routes live under the prefix, so the served contract must
		// advertise it: otherwise "Try it out" calls the bare contract paths, which is
		// exactly what the prefix moved away.
		RoutesOpenapiProperties properties = new RoutesOpenapiProperties();
		Source source = new Source();
		source.setId("books");
		source.setUri(URI.create("https://book-service.example.org"));
		source.setSpecUrl("https://book-service.example.org/v3/api-docs");
		source.setPathPrefix("/book-service");
		properties.setSources(List.of(source));

		StepVerifier.create(locatorFor(properties).getRouteDefinitions()).assertNext((route) -> {
			FilterDefinition rewriteServers = route.getFilters().get(1);
			assertThat(rewriteServers.getName()).isEqualTo("OpenapiModifyResponseBody");
			assertThat(rewriteServers.getArgs().values()).contains("/book-service");
		}).verifyComplete();
	}

	@Test
	void advertisesTheGatewayRootWhenNoPrefixIsConfigured() {
		RoutesOpenapiProperties properties = new RoutesOpenapiProperties();
		Source source = new Source();
		source.setId("petstore");
		source.setUri(URI.create("https://petstore3.swagger.io"));
		source.setSpecUrl("https://petstore3.swagger.io/api/v3/openapi.json");
		properties.setSources(List.of(source));

		StepVerifier.create(locatorFor(properties).getRouteDefinitions())
			.assertNext((route) -> assertThat(route.getFilters().get(1).getArgs().values()).contains("/"))
			.verifyComplete();
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
