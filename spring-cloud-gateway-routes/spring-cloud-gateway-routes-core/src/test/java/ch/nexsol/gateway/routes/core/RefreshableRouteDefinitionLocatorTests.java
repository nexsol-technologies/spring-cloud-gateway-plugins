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

package ch.nexsol.gateway.routes.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AbstractRefreshableRouteDefinitionLocator} covering the caching,
 * refresh and event publication behaviour.
 */
class RefreshableRouteDefinitionLocatorTests {

	private final List<ApplicationEvent> events = new ArrayList<>();

	private final ApplicationEventPublisher publisher = (event) -> this.events.add((ApplicationEvent) event);

	@Test
	void servesEmptyBeforeFirstRefresh() {
		TestLocator locator = new TestLocator(this.publisher, List.of(route("r1")));

		StepVerifier.create(locator.getRouteDefinitions()).verifyComplete();
		assertThat(this.events).isEmpty();
	}

	@Test
	void cachesDefinitionsAndPublishesRefreshEvent() {
		TestLocator locator = new TestLocator(this.publisher, List.of(route("r1"), route("r2")));

		StepVerifier.create(locator.refresh()).verifyComplete();

		StepVerifier.create(locator.getRouteDefinitions().map(RouteDefinition::getId))
			.expectNext("r1", "r2")
			.verifyComplete();
		assertThat(this.events).hasSize(1).allMatch(RefreshRoutesEvent.class::isInstance);
	}

	@Test
	void keepsPreviousSnapshotWhenRefreshFails() {
		TestLocator locator = new TestLocator(this.publisher, List.of(route("r1")));
		StepVerifier.create(locator.refresh()).verifyComplete();

		locator.failNext();
		StepVerifier.create(locator.refresh()).verifyComplete();

		StepVerifier.create(locator.getRouteDefinitions().map(RouteDefinition::getId))
			.expectNext("r1")
			.verifyComplete();
		assertThat(this.events).hasSize(1);
	}

	private static RouteDefinition route(String id) {
		RouteDefinition definition = new RouteDefinition();
		definition.setId(id);
		definition.setUri(URI.create("https://example.org"));
		return definition;
	}

	private static final class TestLocator extends AbstractRefreshableRouteDefinitionLocator {

		private final List<RouteDefinition> definitions;

		private boolean fail;

		private TestLocator(ApplicationEventPublisher publisher, List<RouteDefinition> definitions) {
			super(publisher);
			this.definitions = definitions;
		}

		private void failNext() {
			this.fail = true;
		}

		@Override
		protected Flux<RouteDefinition> loadRouteDefinitions() {
			if (this.fail) {
				return Flux.error(new IllegalStateException("boom"));
			}
			return Flux.fromIterable(this.definitions);
		}

	}

}
