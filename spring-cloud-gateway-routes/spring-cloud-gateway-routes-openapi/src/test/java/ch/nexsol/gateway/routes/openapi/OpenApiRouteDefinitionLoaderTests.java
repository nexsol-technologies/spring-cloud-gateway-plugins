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

package ch.nexsol.gateway.routes.openapi;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link OpenApiRouteDefinitionLoader}, {@link OpenApiRouteDefinitionLocator}
 * and {@link DefaultOpenApiSpecLoader}.
 */
class OpenApiRouteDefinitionLoaderTests {

	private ApplicationEvent lastEvent;

	private final ApplicationEventPublisher publisher = (event) -> this.lastEvent = (ApplicationEvent) event;

	private final OpenApiSpecLoader stubLoader = (url) -> parse();

	private static OpenAPI parse() {
		try {
			String content = new ClassPathResource("openapi/petstore.yaml").getContentAsString(StandardCharsets.UTF_8);
			ParseOptions options = new ParseOptions();
			options.setResolve(true);
			return new OpenAPIV3Parser().readContents(content, null, options).getOpenAPI();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private Source aggregatedSource() {
		Source source = new Source();
		source.setId("petstore");
		source.setUri(URI.create("https://backend.example.org"));
		source.setSpecUrl("stub://petstore");
		source.setMode(RouteGenerationMode.AGGREGATED);
		return source;
	}

	private static OpenapiSourcesLoader sourcesOf(Source... sources) {
		RoutesOpenapiProperties properties = new RoutesOpenapiProperties();
		properties.setSources(List.of(sources));
		return new OpenapiSourcesLoader(properties, new PathMatchingResourcePatternResolver());
	}

	@Test
	void loaderAggregatesRoutesFromEverySource() {
		OpenApiRouteDefinitionLoader loader = new OpenApiRouteDefinitionLoader(this.stubLoader,
				new OpenApiRouteDefinitionMapper(), sourcesOf(aggregatedSource()));

		List<RouteDefinition> routes = loader.load();

		assertThat(routes).extracting(RouteDefinition::getId).containsExactly("petstore");
	}

	/**
	 * A source may be declared for its contract alone, so it joins the OpenAPI hub next
	 * to the services it aggregates while its routes stay declared elsewhere &mdash; by
	 * hand, by the discovery locator, in a route file. Generating routes for it too would
	 * put a second set of overlapping predicates in front of the same backend.
	 */
	@Test
	void loaderGeneratesNoRouteForASourceDeclaredForItsContractAlone() {
		Source documentationOnly = aggregatedSource();
		documentationOnly.setMode(RouteGenerationMode.NO_ROUTE);
		OpenApiRouteDefinitionLoader loader = new OpenApiRouteDefinitionLoader(this.stubLoader,
				new OpenApiRouteDefinitionMapper(), sourcesOf(documentationOnly));

		assertThat(loader.load()).isEmpty();
	}

	@Test
	void loaderKeepsGeneratingForTheOtherSources() {
		Source documentationOnly = aggregatedSource();
		documentationOnly.setId("alert-service");
		documentationOnly.setMode(RouteGenerationMode.NO_ROUTE);
		OpenApiRouteDefinitionLoader loader = new OpenApiRouteDefinitionLoader(this.stubLoader,
				new OpenApiRouteDefinitionMapper(), sourcesOf(documentationOnly, aggregatedSource()));

		assertThat(loader.load()).extracting(RouteDefinition::getId).containsExactly("petstore");
	}

	/**
	 * The document of such a source is not even read here: reading it is the hub's
	 * business, and a contract that cannot be parsed into routes nobody asked for is not
	 * a failure of this loader.
	 */
	@Test
	void loaderDoesNotReadTheDocumentOfASourceItGeneratesNothingFor() {
		Source documentationOnly = aggregatedSource();
		documentationOnly.setMode(RouteGenerationMode.NO_ROUTE);
		OpenApiRouteDefinitionLoader loader = new OpenApiRouteDefinitionLoader((url) -> {
			throw new IllegalStateException("the document must not be read");
		}, new OpenApiRouteDefinitionMapper(), sourcesOf(documentationOnly));

		assertThat(loader.load()).isEmpty();
	}

	@Test
	void loaderIsolatesFailingSources() {
		Source failing = new Source();
		failing.setId("broken");
		failing.setUri(URI.create("https://backend.example.org"));
		failing.setSpecUrl("stub://broken");
		OpenApiSpecLoader partialLoader = (url) -> {
			if (url.equals("stub://broken")) {
				throw new OpenApiRouteException("unreachable");
			}
			return parse();
		};
		OpenApiRouteDefinitionLoader loader = new OpenApiRouteDefinitionLoader(partialLoader,
				new OpenApiRouteDefinitionMapper(), sourcesOf(failing, aggregatedSource()));

		List<RouteDefinition> routes = loader.load();

		// the failing source is skipped, the healthy one still produces its route
		assertThat(routes).extracting(RouteDefinition::getId).containsExactly("petstore");
	}

	@Test
	void locatorCachesRoutesAndPublishesRefreshEvent() {
		OpenApiRouteDefinitionLoader loader = new OpenApiRouteDefinitionLoader(this.stubLoader,
				new OpenApiRouteDefinitionMapper(), sourcesOf(aggregatedSource()));
		OpenApiRouteDefinitionLocator locator = new OpenApiRouteDefinitionLocator(loader, this.publisher);

		StepVerifier.create(locator.getRouteDefinitions()).verifyComplete();
		StepVerifier.create(locator.refresh()).verifyComplete();

		StepVerifier.create(locator.getRouteDefinitions().map(RouteDefinition::getId))
			.expectNext("petstore")
			.verifyComplete();
		assertThat(this.lastEvent).isInstanceOf(RefreshRoutesEvent.class);
	}

	@Test
	void defaultSpecLoaderReadsDocumentFromFile() throws Exception {
		String path = new ClassPathResource("openapi/petstore.yaml").getFile().getAbsolutePath();

		OpenAPI openApi = new DefaultOpenApiSpecLoader().load(path);

		assertThat(openApi.getPaths()).containsKeys("/pets", "/pets/{petId}");
	}

	@Test
	void defaultSpecLoaderFailsOnMissingLocation() {
		assertThatExceptionOfType(OpenApiRouteException.class)
			.isThrownBy(() -> new DefaultOpenApiSpecLoader().load("  "));
	}

}
