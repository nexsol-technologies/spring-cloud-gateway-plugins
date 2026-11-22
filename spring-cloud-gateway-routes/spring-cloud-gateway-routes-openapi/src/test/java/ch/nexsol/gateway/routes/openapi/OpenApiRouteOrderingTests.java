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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the order of the routes generated one per operation. The gateway serves a request
 * with the first route that matches it, and {@code /users/{id}} matches {@code /users/me}
 * too: declared first, it would lend its own metadata and filters &mdash; its public flag
 * above all &mdash; to an operation the contract protects.
 */
class OpenApiRouteOrderingTests {

	private static final String PUBLIC_METADATA_KEY = "public";

	private final OpenApiRouteDefinitionMapper mapper = new OpenApiRouteDefinitionMapper();

	@Test
	void aConcretePathIsRoutedAheadOfTheTemplateThatWouldMatchIt() {
		List<String> ids = ids(routes(false));

		assertThat(ids.indexOf("users_getMe")).isLessThan(ids.indexOf("users_getUser"));
	}

	@Test
	void aPathWithFewerVariablesIsRoutedAheadOfOneWithMore() {
		List<String> ids = ids(routes(false));

		assertThat(ids.indexOf("users_getOwner")).isLessThan(ids.indexOf("users_getMember"));
	}

	@Test
	void theContractOrderIsKeptAmongPathsOfTheSameShape() {
		List<String> ids = ids(routes(false));

		assertThat(ids.indexOf("users_getMe")).isLessThan(ids.indexOf("users_status"));
		assertThat(ids.indexOf("users_getUser")).isLessThan(ids.indexOf("users_getOwner"));
	}

	@Test
	void theProtectedOperationIsNotServedByThePublicRouteDeclaredBeforeIt() {
		List<RouteDefinition> routes = routes(true);

		RouteDefinition first = routes.stream()
			.filter((route) -> route.getPredicates().get(0).getArgs().containsValue("/users/me")
					|| route.getPredicates().get(0).getArgs().containsValue("/users/{id}"))
			.findFirst()
			.orElseThrow();

		assertThat(first.getId()).isEqualTo("users_getMe");
		assertThat(first.getMetadata()).doesNotContainKey(PUBLIC_METADATA_KEY);
	}

	@Test
	void thePublicFlagStaysOnTheOperationThatDeclaresIt() {
		List<RouteDefinition> routes = routes(true);

		assertThat(routes).filteredOn((route) -> route.getMetadata().containsKey(PUBLIC_METADATA_KEY))
			.extracting(RouteDefinition::getId)
			.containsExactly("users_getUser");
	}

	private List<RouteDefinition> routes(boolean publicFromContract) {
		Source source = new Source();
		source.setId("users");
		source.setUri(URI.create("https://backend.example.org"));
		source.setMode(RouteGenerationMode.PER_OPERATION);
		source.setPublicFromContract(publicFromContract);
		return this.mapper.toRouteDefinitions(source, parse("openapi/shadowed-operations.yaml"));
	}

	private static List<String> ids(List<RouteDefinition> routes) {
		return routes.stream().map(RouteDefinition::getId).toList();
	}

	private static OpenAPI parse(String location) {
		try {
			String content = new ClassPathResource(location).getContentAsString(StandardCharsets.UTF_8);
			ParseOptions options = new ParseOptions();
			options.setResolve(true);
			return new OpenAPIV3Parser().readContents(content, null, options).getOpenAPI();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
