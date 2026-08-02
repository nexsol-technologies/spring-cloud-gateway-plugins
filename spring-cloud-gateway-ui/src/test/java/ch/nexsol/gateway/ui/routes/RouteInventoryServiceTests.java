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

package ch.nexsol.gateway.ui.routes;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.CompositeRouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteInventoryServiceTests {

	private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

	@Test
	void listsEveryRouteWithTheSourceItCameFrom() {
		RouteInventoryService service = serviceOver(
				new AlphaRouteDefinitionLocator(definition("alpha", "http://alpha")),
				new ConfigServerRouteDefinitionLocator(definition("bravo", "http://bravo")));

		StepVerifier.create(service.routes()).assertNext((routes) -> {
			assertThat(routes).extracting(RouteView::routeId).containsExactly("alpha", "bravo");
			assertThat(routes).extracting(RouteView::source).containsExactly("Alpha", "Config Server");
			assertThat(routes).extracting(RouteView::uri).containsExactly("http://alpha", "http://bravo");
		}).verifyComplete();
	}

	@Test
	void rendersPredicatesAndFiltersTheWayTheyAreDeclared() {
		RouteDefinition definition = definition("alpha", "http://alpha");
		definition.setPredicates(List.of(predicate("Path", Map.of("_genkey_0", "/alpha/**"))));
		definition.setFilters(List.of(filter("AddRequestHeader", args("name", "X-Tenant", "value", "acme"))));

		StepVerifier.create(serviceOver(new AlphaRouteDefinitionLocator(definition)).routes()).assertNext((routes) -> {
			assertThat(routes.get(0).predicates()).containsExactly("Path=/alpha/**");
			assertThat(routes.get(0).filters()).containsExactly("AddRequestHeader(name=X-Tenant, value=acme)");
		}).verifyComplete();
	}

	@Test
	void rendersPositionalArgumentsAsTheShortcutTheyWereDeclaredAs() {
		assertThat(RouteInventoryService.describe("Path", Map.of("_genkey_0", "/alpha/**")))
			.isEqualTo("Path=/alpha/**");
	}

	@Test
	void rendersNamedArgumentsAsACallSoTheElementNameStaysApart() {
		// A source declaring its arguments by name must not read as
		// "Path=patterns=/alpha".
		assertThat(RouteInventoryService.describe("Path", args("patterns", "/alpha/**", "matchTrailingSlash", "true")))
			.isEqualTo("Path(patterns=/alpha/**, matchTrailingSlash=true)");
	}

	@Test
	void flagsRouteIdsDeclaredByMoreThanOneSource() {
		RouteInventoryService service = serviceOver(
				new AlphaRouteDefinitionLocator(definition("shared", "http://first")),
				new ConfigServerRouteDefinitionLocator(definition("shared", "http://second"),
						definition("unique", "http://unique")));

		StepVerifier.create(service.routes()).assertNext((routes) -> {
			assertThat(routes).filteredOn(RouteView::duplicated)
				.extracting(RouteView::source)
				.containsExactly("Alpha", "Config Server");
			assertThat(routes).filteredOn((route) -> "unique".equals(route.routeId()))
				.allMatch((route) -> !route.duplicated());
		}).verifyComplete();
	}

	@Test
	void ignoresTheCompositeLocatorSoRoutesAreNotCountedTwice() {
		AlphaRouteDefinitionLocator alpha = new AlphaRouteDefinitionLocator(definition("alpha", "http://alpha"));
		RouteInventoryService service = serviceOver(alpha, new CompositeRouteDefinitionLocator(Flux.just(alpha)));

		StepVerifier.create(service.routes())
			.assertNext((routes) -> assertThat(routes).extracting(RouteView::routeId).containsExactly("alpha"))
			.verifyComplete();
	}

	@Test
	void keepsTheOtherSourcesWhenOneCannotBeRead() {
		RouteInventoryService service = serviceOver(new FailingRouteDefinitionLocator(),
				new AlphaRouteDefinitionLocator(definition("alpha", "http://alpha")));

		StepVerifier.create(service.routes())
			.assertNext((routes) -> assertThat(routes).extracting(RouteView::routeId).containsExactly("alpha"))
			.verifyComplete();
	}

	@Test
	void dropsASourceThatDoesNotAnswerInTime() {
		// The silent source comes first: the routes of the ones behind it must still be
		// listed, and the page must not wait on it beyond the bound.
		StepVerifier
			.withVirtualTime(() -> serviceOver(new SilentRouteDefinitionLocator(),
					new AlphaRouteDefinitionLocator(definition("alpha", "http://alpha")))
				.routes())
			.thenAwait(Duration.ofSeconds(5))
			.assertNext((routes) -> assertThat(routes).extracting(RouteView::routeId).containsExactly("alpha"))
			.verifyComplete();
	}

	@Test
	void readsEverySourceOnceAndServesTheSnapshotToTheNextReader() {
		CountingRouteDefinitionLocator locator = new CountingRouteDefinitionLocator();
		RouteInventoryService service = serviceOver(locator);

		StepVerifier.create(service.routes()).expectNextCount(1).verifyComplete();
		StepVerifier.create(service.routes()).expectNextCount(1).verifyComplete();

		assertThat(locator.reads()).isEqualTo(1);
	}

	@Test
	void readsTheSourcesAgainOnceTheGatewayRebuiltItsRouteTable() {
		CountingRouteDefinitionLocator locator = new CountingRouteDefinitionLocator();
		RouteInventoryService service = serviceOver(locator);

		StepVerifier.create(service.routes()).expectNextCount(1).verifyComplete();
		service.onApplicationEvent(new RefreshRoutesEvent(this));
		StepVerifier.create(service.routes()).expectNextCount(1).verifyComplete();

		assertThat(locator.reads()).isEqualTo(2);
	}

	@Test
	void servesThePreviousInventoryInsteadOfWaitingOnTheSourcesAgain() {
		// A source that stopped answering must not hold the page: behind service
		// discovery, reading the sources outlasts what a page load can wait for.
		RouteInventoryService service = serviceOver(new SilentAfterFirstReadRouteDefinitionLocator());

		StepVerifier.create(service.routes()).expectNextCount(1).verifyComplete();
		service.onApplicationEvent(new RefreshRoutesEvent(this));

		StepVerifier.create(service.routes())
			.assertNext((routes) -> assertThat(routes).extracting(RouteView::routeId).containsExactly("counted"))
			.expectComplete()
			// Well inside the bound a single source is given to answer: the view was
			// served from the previous inventory, not from the read it just triggered.
			.verify(Duration.ofSeconds(1));
	}

	@Test
	void readsTheSourcesOnceForABurstOfRefreshEvents() {
		CountingRouteDefinitionLocator locator = new CountingRouteDefinitionLocator();
		RouteInventoryService service = serviceOver(locator);

		StepVerifier.create(service.routes()).expectNextCount(1).verifyComplete();
		service.onApplicationEvent(new RefreshRoutesEvent(this));
		service.onApplicationEvent(new RefreshRoutesEvent(this));
		service.onApplicationEvent(new RefreshRoutesEvent(this));

		StepVerifier.create(service.routes()).expectNextCount(1).verifyComplete();
		StepVerifier.create(service.routes()).expectNextCount(1).verifyComplete();

		// A discovery heartbeat publishes a refresh event on every tick: a burst of them
		// costs one read, not one per event nor one per view rendered.
		assertThat(locator.reads()).isEqualTo(2);
	}

	@Test
	void refreshingTheViewReadsTheSourcesAgain() {
		CountingRouteDefinitionLocator locator = new CountingRouteDefinitionLocator();
		RouteInventoryService service = serviceOver(locator);

		StepVerifier.create(service.routes()).expectNextCount(1).verifyComplete();
		StepVerifier.create(service.refreshedRoutes()).expectNextCount(1).verifyComplete();
		StepVerifier.create(service.routes()).expectNextCount(1).verifyComplete();

		// Once for the first display, once for the explicit refresh, and the snapshot
		// left behind serves the display that follows.
		assertThat(locator.reads()).isEqualTo(2);
	}

	@Test
	void reportsNoRouteWhenNoSourceIsRegistered() {
		StepVerifier.create(serviceOver().routes())
			.assertNext((routes) -> assertThat(routes).isEmpty())
			.verifyComplete();
	}

	@Test
	void reloadAsksTheGatewayToRebuildItsRouteTable() {
		serviceOver().reload();

		verify(this.publisher).publishEvent(any(RefreshRoutesEvent.class));
	}

	@Test
	void namesASourceAfterItsLocatorClass() {
		assertThat(RouteInventoryService.sourceName(new ConfigServerRouteDefinitionLocator()))
			.isEqualTo("Config Server");
		assertThat(RouteInventoryService.sourceName(new FailingRouteDefinitionLocator())).isEqualTo("Failing");
	}

	@Test
	void namesALocatorDeclaredAsALambdaGenerically() {
		assertThat(RouteInventoryService.sourceName(Flux::empty)).isEqualTo("Custom");
	}

	@Test
	void describesAnElementWithoutArgumentsByItsNameAlone() {
		assertThat(RouteInventoryService.describe("Weight", Map.of())).isEqualTo("Weight");
		assertThat(RouteInventoryService.describe("Weight", null)).isEqualTo("Weight");
	}

	@SuppressWarnings("unchecked")
	private RouteInventoryService serviceOver(RouteDefinitionLocator... locators) {
		ObjectProvider<RouteDefinitionLocator> provider = mock(ObjectProvider.class);
		when(provider.orderedStream()).thenAnswer((invocation) -> Stream.of(locators));
		return new RouteInventoryService(provider, this.publisher);
	}

	private static RouteDefinition definition(String id, String uri) {
		RouteDefinition definition = new RouteDefinition();
		definition.setId(id);
		definition.setUri(URI.create(uri));
		return definition;
	}

	private static PredicateDefinition predicate(String name, Map<String, String> args) {
		PredicateDefinition predicate = new PredicateDefinition();
		predicate.setName(name);
		predicate.setArgs(args);
		return predicate;
	}

	private static FilterDefinition filter(String name, Map<String, String> args) {
		FilterDefinition filter = new FilterDefinition();
		filter.setName(name);
		filter.setArgs(args);
		return filter;
	}

	private static Map<String, String> args(String firstName, String firstValue, String secondName,
			String secondValue) {
		Map<String, String> args = new LinkedHashMap<>();
		args.put(firstName, firstValue);
		args.put(secondName, secondValue);
		return args;
	}

	/**
	 * Base for the fixture sources. Each fixture needs its own type because the displayed
	 * source name is derived from the locator class name.
	 */
	private abstract static class FixtureLocator implements RouteDefinitionLocator {

		private final List<RouteDefinition> definitions;

		FixtureLocator(RouteDefinition... definitions) {
			this.definitions = List.of(definitions);
		}

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			return Flux.fromIterable(this.definitions);
		}

	}

	private static final class AlphaRouteDefinitionLocator extends FixtureLocator {

		AlphaRouteDefinitionLocator(RouteDefinition... definitions) {
			super(definitions);
		}

	}

	private static final class ConfigServerRouteDefinitionLocator extends FixtureLocator {

		ConfigServerRouteDefinitionLocator(RouteDefinition... definitions) {
			super(definitions);
		}

	}

	private static final class FailingRouteDefinitionLocator implements RouteDefinitionLocator {

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			return Flux.error(new IllegalStateException("source down"));
		}

	}

	/**
	 * A source that never answers, as an unreachable one probed over the network does.
	 */
	private static final class SilentRouteDefinitionLocator implements RouteDefinitionLocator {

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			return Flux.never();
		}

	}

	/**
	 * Answers the first read, then never answers again, as a source whose registry became
	 * unreachable does.
	 */
	private static final class SilentAfterFirstReadRouteDefinitionLocator implements RouteDefinitionLocator {

		private final AtomicInteger reads = new AtomicInteger();

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			return Flux.defer(() -> (this.reads.getAndIncrement() == 0)
					? Flux.just(definition("counted", "http://counted")) : Flux.never());
		}

	}

	/** Counts how many times the source was actually read, to observe the caching. */
	private static final class CountingRouteDefinitionLocator implements RouteDefinitionLocator {

		private final AtomicInteger reads = new AtomicInteger();

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			return Flux.defer(() -> {
				this.reads.incrementAndGet();
				return Flux.just(definition("counted", "http://counted"));
			});
		}

		int reads() {
			return this.reads.get();
		}

	}

}
