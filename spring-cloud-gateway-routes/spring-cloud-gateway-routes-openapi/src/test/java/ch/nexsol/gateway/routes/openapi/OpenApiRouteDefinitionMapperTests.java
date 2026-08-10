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
import java.util.Map;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Tests for {@link OpenApiRouteDefinitionMapper} covering both generation modes and the
 * application of metadata and filters.
 */
class OpenApiRouteDefinitionMapperTests {

	private final OpenApiRouteDefinitionMapper mapper = new OpenApiRouteDefinitionMapper();

	private OpenAPI openApi;

	@BeforeEach
	void loadDocument() throws Exception {
		String content = new ClassPathResource("openapi/petstore.yaml").getContentAsString(StandardCharsets.UTF_8);
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		this.openApi = new OpenAPIV3Parser().readContents(content, null, options).getOpenAPI();
		assertThat(this.openApi).isNotNull();
	}

	private Source source(RouteGenerationMode mode) {
		Source source = new Source();
		source.setId("petstore");
		source.setUri(URI.create("https://backend.example.org"));
		source.setMode(mode);
		source.setMetadata(Map.of("tier", "gold"));
		source.setFilters(List.of("Retry=3"));
		return source;
	}

	@Test
	void perOperationCreatesOneRoutePerOperation() {
		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source(RouteGenerationMode.PER_OPERATION),
				this.openApi);

		assertThat(routes).hasSize(3);
		assertThat(routes).extracting(RouteDefinition::getId)
			.containsExactlyInAnyOrder("petstore_listPets", "petstore_createPet", "petstore_getPet");

		RouteDefinition getPet = routes.stream().filter((r) -> r.getId().equals("petstore_getPet")).findFirst().get();
		assertThat(getPet.getUri()).hasToString("https://backend.example.org");
		assertThat(getPet.getPredicates()).extracting(PredicateDefinition::getName).containsExactly("Path", "Method");
		assertThat(getPet.getMetadata()).containsEntry("tier", "gold");
		// the server base path (/api/v3) is added back as a PrefixPath filter, before the
		// configured filters
		assertThat(getPet.getFilters()).extracting(FilterDefinition::getName).containsExactly("PrefixPath", "Retry");
		assertThat(getPet.getFilters().get(0).getArgs().values()).contains("/api/v3");
	}

	@Test
	void aggregatedCreatesSingleRouteWithAllPathsAndMethods() {
		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source(RouteGenerationMode.AGGREGATED),
				this.openApi);

		assertThat(routes).hasSize(1);
		RouteDefinition route = routes.get(0);
		assertThat(route.getId()).isEqualTo("petstore");

		PredicateDefinition pathPredicate = route.getPredicates().get(0);
		assertThat(pathPredicate.getName()).isEqualTo("Path");
		// both paths are gathered as _genkey_0 / _genkey_1 by the shorthand constructor
		assertThat(pathPredicate.getArgs().values()).containsExactlyInAnyOrder("/pets", "/pets/{petId}");

		PredicateDefinition methodPredicate = route.getPredicates().get(1);
		assertThat(methodPredicate.getName()).isEqualTo("Method");
		assertThat(methodPredicate.getArgs().values()).containsExactlyInAnyOrder("GET", "POST");
	}

	@Test
	void pathPrefixMovesTheContractPathsAsideAndIsRemovedBeforeForwarding() {
		// Two contracts may both declare /pets: the prefix is what keeps them apart on
		// the
		// gateway, and the backend must still see the path its contract declares.
		Source source = source(RouteGenerationMode.PER_OPERATION);
		source.setPathPrefix("/pet-service");

		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source, this.openApi);

		RouteDefinition getPet = routes.stream().filter((r) -> r.getId().equals("petstore_getPet")).findFirst().get();
		assertThat(getPet.getPredicates().get(0).getArgs().values()).contains("/pet-service/pets/{petId}");
		// RewritePath drops the prefix, then PrefixPath adds the backend base path back.
		assertThat(getPet.getFilters()).extracting(FilterDefinition::getName)
			.containsExactly("RewritePath", "PrefixPath", "Retry");
		assertThat(getPet.getFilters().get(0).getArgs().values()).contains("/pet-service(?<remaining>/?.*)",
				"${remaining}");
		assertThat(getPet.getFilters().get(1).getArgs().values()).contains("/api/v3");
	}

	@Test
	void generatingTheRoutesDoesNotValidateTheTrafficUnlessAsked() {
		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source(RouteGenerationMode.AGGREGATED),
				this.openApi);

		assertThat(routes.get(0).getFilters()).extracting(FilterDefinition::getName)
			.doesNotContain("OpenapiValidation");
	}

	@Test
	void validateAttachesTheValidationFilterReusingTheContractOfTheSource() {
		Source source = source(RouteGenerationMode.AGGREGATED);
		source.setSpecUrl("classpath:openapi/petstore.yaml");
		source.setValidate(true);

		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source, this.openApi);

		FilterDefinition validation = routes.get(0).getFilters().get(0);
		assertThat(validation.getName()).isEqualTo("OpenapiValidation");
		assertThat(validation.getArgs()).containsExactly(entry("specUrl", "classpath:openapi/petstore.yaml"));
	}

	@Test
	void validateAttachesTheValidationFilterAheadOfEveryOtherOne() {
		// A request breaking the contract must be denied before a retry or a rate limiter
		// budget is spent on it.
		Source source = source(RouteGenerationMode.PER_OPERATION);
		source.setSpecUrl("classpath:openapi/petstore.yaml");
		source.setPathPrefix("/pet-service");
		source.setValidate(true);

		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source, this.openApi);

		RouteDefinition getPet = routes.stream().filter((r) -> r.getId().equals("petstore_getPet")).findFirst().get();
		assertThat(getPet.getFilters()).extracting(FilterDefinition::getName)
			.containsExactly("OpenapiValidation", "RewritePath", "PrefixPath", "Retry");
	}

	@Test
	void validatePassesTheGatewaySidePrefixRatherThanTheBackendBasePath() {
		Source source = source(RouteGenerationMode.AGGREGATED);
		source.setSpecUrl("classpath:openapi/petstore.yaml");
		// A prefix written without its leading slash is normalized, like everywhere else.
		source.setPathPrefix("pet-service");
		source.setValidate(true);

		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source, this.openApi);

		FilterDefinition validation = routes.get(0).getFilters().get(0);
		assertThat(validation.getArgs()).containsEntry("specUrl", "classpath:openapi/petstore.yaml")
			// '/pet-service', not the '/api/v3' base path the contract servers declare.
			.containsEntry("pathPrefix", "/pet-service");
	}

	@Test
	void pathPrefixAppliesToEveryPathOfAnAggregatedRoute() {
		Source source = source(RouteGenerationMode.AGGREGATED);
		source.setPathPrefix("pet-service");

		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source, this.openApi);

		// a prefix written without its leading slash is normalized like the base path
		assertThat(routes.get(0).getPredicates().get(0).getArgs().values())
			.containsExactlyInAnyOrder("/pet-service/pets", "/pet-service/pets/{petId}");
	}

	@Test
	void noPrefixLeavesThePathsAndFiltersUntouched() {
		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source(RouteGenerationMode.AGGREGATED),
				this.openApi);

		assertThat(routes.get(0).getPredicates().get(0).getArgs().values()).contains("/pets");
		assertThat(routes.get(0).getFilters()).extracting(FilterDefinition::getName)
			.containsExactly("PrefixPath", "Retry");
	}

	@Test
	void explicitBasePathOverridesTheDocumentServer() {
		Source source = source(RouteGenerationMode.AGGREGATED);
		source.setBasePath("custom/v2");

		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source, this.openApi);

		FilterDefinition prefix = routes.get(0).getFilters().get(0);
		assertThat(prefix.getName()).isEqualTo("PrefixPath");
		assertThat(prefix.getArgs().values()).contains("/custom/v2");
	}

	@Test
	void emptyBasePathDisablesThePrefix() {
		Source source = source(RouteGenerationMode.AGGREGATED);
		source.setBasePath("");

		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source, this.openApi);

		assertThat(routes.get(0).getFilters()).extracting(FilterDefinition::getName).doesNotContain("PrefixPath");
	}

	@Test
	void doesNotAddPrefixPathForRootServer() throws Exception {
		String content = new ClassPathResource("openapi/root-server.yaml").getContentAsString(StandardCharsets.UTF_8);
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		OpenAPI rootServer = new OpenAPIV3Parser().readContents(content, null, options).getOpenAPI();

		List<RouteDefinition> routes = this.mapper.toRouteDefinitions(source(RouteGenerationMode.AGGREGATED),
				rootServer);

		assertThat(routes.get(0).getFilters()).extracting(FilterDefinition::getName).doesNotContain("PrefixPath");
	}

}
