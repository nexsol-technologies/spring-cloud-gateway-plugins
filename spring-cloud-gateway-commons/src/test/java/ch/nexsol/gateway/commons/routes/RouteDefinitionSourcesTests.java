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

package ch.nexsol.gateway.commons.routes;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.route.CompositeRouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import static org.assertj.core.api.Assertions.assertThat;

class RouteDefinitionSourcesTests {

	@Test
	void namesASourceAfterItsLocatorClass() {
		assertThat(RouteDefinitionSources.name(new ConfigServerRouteDefinitionLocator())).isEqualTo("Config Server");
		assertThat(RouteDefinitionSources.name(new DatabaseRouteDefinitionRepository())).isEqualTo("Database");
	}

	@Test
	void namesALocatorDeclaredAsALambdaGenerically() {
		assertThat(RouteDefinitionSources.name(Flux::empty)).isEqualTo("Custom");
	}

	@Test
	void leavesTheCompositeOutOfTheSources() {
		RouteDefinitionLocator source = new ConfigServerRouteDefinitionLocator();
		RouteDefinitionLocator composite = new CompositeRouteDefinitionLocator(Flux.just(source));
		assertThat(RouteDefinitionSources.sources(List.of(composite, source))).containsExactly(source);
	}

	@Test
	void boundsTheWholeReadOfASource() {
		RouteDefinitionLocator slow = () -> Flux.just(new RouteDefinition()).delayElements(Duration.ofSeconds(5));
		StepVerifier.withVirtualTime(() -> RouteDefinitionSources.read(slow, Duration.ofSeconds(1)))
			.thenAwait(Duration.ofSeconds(5))
			.expectError(TimeoutException.class)
			.verify();
	}

	static class ConfigServerRouteDefinitionLocator implements RouteDefinitionLocator {

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			return Flux.empty();
		}

	}

	static class DatabaseRouteDefinitionRepository implements RouteDefinitionLocator {

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			return Flux.empty();
		}

	}

}
