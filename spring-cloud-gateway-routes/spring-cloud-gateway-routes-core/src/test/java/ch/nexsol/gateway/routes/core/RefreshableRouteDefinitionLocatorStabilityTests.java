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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests what a source read again and again costs the gateway when nothing in it changed,
 * and where the rebuild of the route table is triggered from.
 * <p>
 * The gateway gives a route declared without an id a random one, written on the very
 * definition the locator handed out. A source read anew declares the route without an id
 * again: compared with the definitions now carrying one, it would differ on every read.
 */
class RefreshableRouteDefinitionLocatorStabilityTests {

	private final List<ApplicationEvent> events = new CopyOnWriteArrayList<>();

	private final List<String> publishingThreads = new CopyOnWriteArrayList<>();

	private final ApplicationEventPublisher publisher = (event) -> {
		this.publishingThreads.add(Thread.currentThread().getName());
		this.events.add((ApplicationEvent) event);
	};

	@Test
	void aSourceOfRoutesWithoutIdPublishesNothingWhileItDoesNotChange() {
		TestLocator locator = new TestLocator(this.publisher, () -> List.of(route(null, "https://example.org")));
		StepVerifier.create(locator.refresh()).verifyComplete();
		giveAnIdLikeTheGatewayDoes(locator);

		StepVerifier.create(locator.refresh()).verifyComplete();
		StepVerifier.create(locator.refresh()).verifyComplete();

		assertThat(this.events).hasSize(1);
	}

	@Test
	void aRouteWithoutIdKeepsTheOneItWasGivenWhileTheSourceDoesNotChange() {
		TestLocator locator = new TestLocator(this.publisher, () -> List.of(route(null, "https://example.org")));
		StepVerifier.create(locator.refresh()).verifyComplete();
		giveAnIdLikeTheGatewayDoes(locator);

		StepVerifier.create(locator.refresh()).verifyComplete();

		StepVerifier.create(locator.getRouteDefinitions().map(RouteDefinition::getId))
			.expectNext("generated-0")
			.verifyComplete();
	}

	@Test
	void aSourceThatChangedIsStillPublished() {
		List<RouteDefinition> current = new ArrayList<>(List.of(route(null, "https://example.org")));
		TestLocator locator = new TestLocator(this.publisher, () -> List.copyOf(current));
		StepVerifier.create(locator.refresh()).verifyComplete();
		giveAnIdLikeTheGatewayDoes(locator);

		current.set(0, route(null, "https://elsewhere.example.org"));
		StepVerifier.create(locator.refresh()).verifyComplete();

		assertThat(this.events).hasSize(2);
		StepVerifier.create(locator.getRouteDefinitions().map(RouteDefinition::getUri))
			.expectNext(URI.create("https://elsewhere.example.org"))
			.verifyComplete();
	}

	@Test
	void theRouteTableIsNotRebuiltOnTheThreadThatReadTheSource() {
		Scheduler io = Schedulers.newSingle("source-io");
		try {
			TestLocator locator = new TestLocator(this.publisher, () -> List.of(route("r1", "https://example.org")),
					io);

			StepVerifier.create(locator.refresh()).verifyComplete();

			// Publishing the event rebuilds the route table on the publishing thread. The
			// thread that read the source may be an event loop serving requests.
			assertThat(this.publishingThreads).hasSize(1).noneMatch((thread) -> thread.startsWith("source-io"));
		}
		finally {
			io.dispose();
		}
	}

	private static void giveAnIdLikeTheGatewayDoes(TestLocator locator) {
		List<RouteDefinition> served = locator.getRouteDefinitions().collectList().block();
		for (int i = 0; i < served.size(); i++) {
			if (served.get(i).getId() == null) {
				served.get(i).setId("generated-" + i);
			}
		}
	}

	private static RouteDefinition route(String id, String uri) {
		RouteDefinition definition = new RouteDefinition();
		definition.setId(id);
		definition.setUri(URI.create(uri));
		return definition;
	}

	private static final class TestLocator extends AbstractRefreshableRouteDefinitionLocator {

		private final Supplier<List<RouteDefinition>> source;

		private final Scheduler scheduler;

		private TestLocator(ApplicationEventPublisher publisher, Supplier<List<RouteDefinition>> source) {
			this(publisher, source, Schedulers.immediate());
		}

		private TestLocator(ApplicationEventPublisher publisher, Supplier<List<RouteDefinition>> source,
				Scheduler scheduler) {
			super(publisher);
			this.source = source;
			this.scheduler = scheduler;
		}

		@Override
		protected Flux<RouteDefinition> loadRouteDefinitions() {
			return Flux.defer(() -> Flux.fromIterable(this.source.get())).subscribeOn(this.scheduler);
		}

	}

}
