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
import java.util.List;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link OpenapiSourcesLoader}, which merges the sources configured inline with
 * those declared in the documents the locations point at.
 */
class OpenapiSourcesLoaderTests {

	private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

	private final RoutesOpenapiProperties properties = new RoutesOpenapiProperties();

	@Test
	void inlineSourcesAreReturnedWhenNoLocationIsConfigured() {
		this.properties.setSources(List.of(inlineSource()));

		assertThat(load()).extracting(Source::getId).containsExactly("petstore");
	}

	@Test
	void documentSourcesAreAddedToTheInlineOnes() {
		this.properties.setSources(List.of(inlineSource()));
		this.properties.setSourcesLocations(List.of("classpath:openapi/partner-sources.yaml"));

		assertThat(load()).extracting(Source::getId).containsExactly("petstore", "partner");
	}

	@Test
	void kebabCasedFieldsOfADocumentAreBound() {
		this.properties.setSourcesLocations(List.of("classpath:openapi/partner-sources.yaml"));

		Source partner = load().get(0);

		assertThat(partner.getSpecUrl()).isEqualTo("https://partner.example.org/v3/api-docs");
		assertThat(partner.getBasePath()).isEqualTo("/api/v3");
		assertThat(partner.getMode()).isEqualTo(RouteGenerationMode.PER_OPERATION);
		assertThat(partner.getUri()).isEqualTo(URI.create("https://partner.example.org"));
		assertThat(partner.getMetadata()).containsEntry("team", "partners");
		assertThat(partner.getFilters()).containsExactly("Retry=3");
	}

	@Test
	void severalDocumentsAreReadInTheOrderTheyAreDeclared() {
		this.properties.setSourcesLocations(
				List.of("classpath:openapi/partner-sources.yaml", "classpath:openapi/partner-sources.yaml"));

		assertThat(load()).extracting(Source::getId).containsExactly("partner", "partner");
	}

	@Test
	void anUnreachableDocumentIsSkippedRatherThanDroppingTheOthers() {
		this.properties.setSources(List.of(inlineSource()));
		this.properties.setSourcesLocations(
				List.of("classpath:openapi/does-not-exist.yaml", "classpath:openapi/partner-sources.yaml"));

		assertThat(load()).extracting(Source::getId).containsExactly("petstore", "partner");
	}

	@Test
	void aDocumentWithoutSourcesArrayIsSkipped() {
		this.properties.setSources(List.of(inlineSource()));
		this.properties.setSourcesLocations(List.of("classpath:openapi/petstore.yaml"));

		assertThat(load()).extracting(Source::getId).containsExactly("petstore");
	}

	private List<Source> load() {
		return new OpenapiSourcesLoader(this.properties, this.resolver).load();
	}

	private static Source inlineSource() {
		Source source = new Source();
		source.setId("petstore");
		source.setUri(URI.create("https://petstore.example.org"));
		source.setSpecUrl("https://petstore.example.org/v3/api-docs");
		return source;
	}

}
