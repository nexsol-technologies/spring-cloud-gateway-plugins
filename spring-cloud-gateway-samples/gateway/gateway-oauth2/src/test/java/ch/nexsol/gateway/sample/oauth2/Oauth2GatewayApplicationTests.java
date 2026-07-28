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

package ch.nexsol.gateway.sample.oauth2;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the sample declares the routes it documents, with the
 * {@code AuthorizationToken} filter on the protected ones. The answers those routes give
 * depend on the authorization server the README asks for, which is not started here.
 */
@SpringBootTest
class Oauth2GatewayApplicationTests {

	@Autowired
	RouteDefinitionLocator routeDefinitionLocator;

	@Test
	void shouldDeclareTheDocumentedRoutes() {
		StepVerifier.create(this.routeDefinitionLocator.getRouteDefinitions().map(RouteDefinition::getId).collectList())
			.assertNext((ids) -> assertThat(ids).contains("token_issuer_granted", "token_issuer_denied", "token_roles",
					"basic_to_bearer"))
			.verifyComplete();
	}

	@Test
	void shouldGuardEveryTokenRouteWithTheAuthorizationTokenFilter() {
		StepVerifier
			.create(this.routeDefinitionLocator.getRouteDefinitions()
				.filter((definition) -> definition.getId().startsWith("token_"))
				.map((definition) -> definition.getFilters().stream().map(FilterDefinition::getName).toList())
				.collectList())
			.assertNext((filtersPerRoute) -> assertThat(filtersPerRoute).isNotEmpty()
				.allSatisfy((filters) -> assertThat(filters).contains("AuthorizationToken")))
			.verifyComplete();
	}

}
