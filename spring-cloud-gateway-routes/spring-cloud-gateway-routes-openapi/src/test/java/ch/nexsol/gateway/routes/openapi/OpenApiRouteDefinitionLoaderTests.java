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

	@Test
	void loaderAggregatesRoutesFromEverySource() {
		OpenApiRouteDefinitionLoader loader = new OpenApiRouteDefinitionLoader(this.stubLoader,
				new OpenApiRouteDefinitionMapper(), List.of(aggregatedSource()));

		List<RouteDefinition> routes = loader.load();

		assertThat(routes).extracting(RouteDefinition::getId).containsExactly("petstore");
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
				new OpenApiRouteDefinitionMapper(), List.of(failing, aggregatedSource()));

		List<RouteDefinition> routes = loader.load();

		// the failing source is skipped, the healthy one still produces its route
		assertThat(routes).extracting(RouteDefinition::getId).containsExactly("petstore");
	}

	@Test
	void locatorCachesRoutesAndPublishesRefreshEvent() {
		OpenApiRouteDefinitionLoader loader = new OpenApiRouteDefinitionLoader(this.stubLoader,
				new OpenApiRouteDefinitionMapper(), List.of(aggregatedSource()));
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
