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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the contracts of several sources are read side by side. Read one after the
 * other, every source out of reach adds its whole connection timeout to the refresh, and
 * to the startup of the gateway.
 */
class OpenApiRouteDefinitionLoaderConcurrencyTests {

	@Test
	void readsTheContractsOfSeveralSourcesSideBySide() {
		CountDownLatch bothReading = new CountDownLatch(2);
		AtomicBoolean overlapped = new AtomicBoolean(true);
		OpenApiSpecLoader specLoader = (url) -> {
			bothReading.countDown();
			if (!await(bothReading)) {
				overlapped.set(false);
			}
			return parse();
		};
		OpenApiRouteDefinitionLoader loader = new OpenApiRouteDefinitionLoader(specLoader,
				new OpenApiRouteDefinitionMapper(), sourcesOf(source("first"), source("second")));

		List<RouteDefinition> routes = loader.load();

		assertThat(routes).extracting(RouteDefinition::getId).containsExactly("first", "second");
		assertThat(overlapped).isTrue();
	}

	@Test
	void keepsTheRoutesInTheOrderOfTheSourcesWhateverAnswersFirst() {
		OpenApiSpecLoader specLoader = (url) -> {
			if (url.endsWith("slow")) {
				sleep(200);
			}
			return parse();
		};
		OpenApiRouteDefinitionLoader loader = new OpenApiRouteDefinitionLoader(specLoader,
				new OpenApiRouteDefinitionMapper(), sourcesOf(source("slow"), source("fast"), source("faster")));

		assertThat(loader.load()).extracting(RouteDefinition::getId).containsExactly("slow", "fast", "faster");
	}

	@Test
	void aSourceThatFailsStillLeavesTheOthersTheirRoutes() {
		OpenApiSpecLoader specLoader = (url) -> {
			if (url.endsWith("broken")) {
				throw new OpenApiRouteException("unreachable");
			}
			return parse();
		};
		OpenApiRouteDefinitionLoader loader = new OpenApiRouteDefinitionLoader(specLoader,
				new OpenApiRouteDefinitionMapper(), sourcesOf(source("first"), source("broken"), source("last")));

		assertThat(loader.load()).extracting(RouteDefinition::getId).containsExactly("first", "last");
	}

	private static boolean await(CountDownLatch latch) {
		try {
			return latch.await(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static Source source(String id) {
		Source source = new Source();
		source.setId(id);
		source.setUri(URI.create("https://backend.example.org"));
		source.setSpecUrl("stub://" + id);
		source.setMode(RouteGenerationMode.AGGREGATED);
		return source;
	}

	private static OpenapiSourcesLoader sourcesOf(Source... sources) {
		RoutesOpenapiProperties properties = new RoutesOpenapiProperties();
		properties.setSources(List.of(sources));
		return new OpenapiSourcesLoader(properties, new PathMatchingResourcePatternResolver());
	}

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

}
