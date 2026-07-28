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

package ch.nexsol.gateway.sample.routesfiles;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the YAML and JSON route files are both read, and aggregated with the routes
 * declared in the properties. The file on disk is not asserted: it lives next to the
 * module rather than on the test classpath, so the working directory decides whether it
 * is there.
 */
@SpringBootTest
class RoutesFilesGatewayApplicationTests {

	@Autowired
	RouteDefinitionLocator routeDefinitionLocator;

	@Test
	void shouldAggregateTheFileRoutesWithThePropertiesOnes() {
		StepVerifier.create(this.routeDefinitionLocator.getRouteDefinitions().map(RouteDefinition::getId).collectList())
			.assertNext((ids) -> assertThat(ids).contains("files_httpbin_get", "files_httpbin_beta", "files_service_a",
					"properties_route"))
			.verifyComplete();
	}

	@Test
	void shouldReadTheMetadataDeclaredInTheFiles() {
		StepVerifier
			.create(this.routeDefinitionLocator.getRouteDefinitions()
				.filter((definition) -> "files_httpbin_get".equals(definition.getId()))
				.single())
			.assertNext((definition) -> assertThat(definition.getMetadata()).containsEntry("source", "files")
				.containsEntry("tier", "gold"))
			.verifyComplete();
	}

}
