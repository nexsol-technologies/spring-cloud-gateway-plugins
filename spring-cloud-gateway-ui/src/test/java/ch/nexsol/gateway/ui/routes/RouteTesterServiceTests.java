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

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.HostRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouteTesterServiceTests {

	@Test
	void picksTheFirstRouteWhosePredicatesAllMatch() {
		RouteTesterService service = testerOver(route("other", "/other/**", HttpMethod.GET),
				route("alpha", "/alpha/**", HttpMethod.GET), route("alpha-too", "/alpha/**", HttpMethod.GET));

		StepVerifier.create(service.test("GET", "/alpha/orders", null)).assertNext((report) -> {
			assertThat(report.error()).isNull();
			assertThat(report.method()).isEqualTo("GET");
			assertThat(report.uri()).isEqualTo("http://localhost/alpha/orders");
			assertThat(report.matchedRouteId()).isEqualTo("alpha");
			assertThat(report.routes()).extracting(RouteMatch::routeId).containsExactly("other", "alpha", "alpha-too");
			assertThat(report.routes()).filteredOn(RouteMatch::matched)
				.extracting(RouteMatch::routeId)
				.containsExactly("alpha", "alpha-too");
		}).verifyComplete();
	}

	@Test
	void reportsWhichPredicateOfANonMatchingRouteFailed() {
		RouteTesterService service = testerOver(route("alpha", "/alpha/**", HttpMethod.POST));

		StepVerifier.create(service.test("GET", "/alpha/orders", null)).assertNext((report) -> {
			assertThat(report.matchedRouteId()).isNull();
			RouteMatch alpha = report.routes().get(0);
			assertThat(alpha.matched()).isFalse();
			assertThat(alpha.predicates()).hasSize(2);
			assertThat(alpha.predicates()).filteredOn(PredicateOutcome::matched)
				.singleElement()
				.satisfies((outcome) -> assertThat(outcome.description()).contains("/alpha/**"));
			assertThat(alpha.predicates()).filteredOn((outcome) -> !outcome.matched())
				.singleElement()
				.satisfies((outcome) -> assertThat(outcome.description()).contains("POST"));
		}).verifyComplete();
	}

	@Test
	void reportsNoMatchWhenNothingInTheRouteTableAccceptsTheRequest() {
		RouteTesterService service = testerOver(route("alpha", "/alpha/**", HttpMethod.GET));

		StepVerifier.create(service.test("GET", "/nowhere", null)).assertNext((report) -> {
			assertThat(report.matchedRouteId()).isNull();
			assertThat(report.routes()).hasSize(1);
			assertThat(report.routes().get(0).matched()).isFalse();
		}).verifyComplete();
	}

	@Test
	void reportsAnEmptyRouteTableWithoutFailing() {
		StepVerifier.create(testerOver().test("GET", "/alpha", null)).assertNext((report) -> {
			assertThat(report.error()).isNull();
			assertThat(report.routes()).isEmpty();
			assertThat(report.matchedRouteId()).isNull();
		}).verifyComplete();
	}

	@Test
	void usesTheHostHeaderAsTheRequestHost() {
		HostRoutePredicateFactory factory = new HostRoutePredicateFactory();
		HostRoutePredicateFactory.Config config = new HostRoutePredicateFactory.Config();
		config.setPatterns(List.of("api.example.com"));
		Route route = Route.async()
			.id("tenant")
			.uri("http://tenant")
			.order(0)
			.asyncPredicate(AsyncPredicate.from(factory.apply(config)))
			.build();

		StepVerifier.create(testerOver(route).test("GET", "/anything", "Host: api.example.com"))
			.assertNext((report) -> {
				assertThat(report.uri()).isEqualTo("http://api.example.com/anything");
				assertThat(report.matchedRouteId()).isEqualTo("tenant");
			})
			.verifyComplete();
	}

	@Test
	void reportsTheFailureWhenTheDescribedRequestCannotBeBuilt() {
		// A broken percent-escape is the one thing the URI builder refuses to fix up.
		StepVerifier.create(testerOver(route("alpha", "/**", HttpMethod.GET)).test("GET", "/orders%zz", null))
			.assertNext((report) -> {
				assertThat(report.error()).isNotNull();
				assertThat(report.uri()).isEqualTo("/orders%zz");
				assertThat(report.routes()).isEmpty();
				assertThat(report.matchedRouteId()).isNull();
			})
			.verifyComplete();
	}

	@Test
	void saysSoWhenTheGatewayExposesNoRouteTable() {
		StepVerifier.create(testerFor(null).test("GET", "/alpha", null)).assertNext((report) -> {
			assertThat(report.error()).isEqualTo(RouteTesterService.NO_ROUTE_TABLE);
			assertThat(report.routes()).isEmpty();
		}).verifyComplete();
	}

	@Test
	void encodesTheCharactersATypedPathMayCarry() {
		StepVerifier.create(testerOver().test("GET", "/orders with spaces", null))
			.assertNext((report) -> assertThat(report.uri()).isEqualTo("http://localhost/orders%20with%20spaces"))
			.verifyComplete();
	}

	@Test
	void defaultsToAGetOnTheRootWhenNothingIsDescribed() {
		StepVerifier.create(testerOver().test(null, null, null)).assertNext((report) -> {
			assertThat(report.method()).isEqualTo("GET");
			assertThat(report.uri()).isEqualTo("http://localhost/");
		}).verifyComplete();
	}

	@Test
	void readsOneHeaderPerLineAndSkipsTheLinesThatAreNotHeaders() {
		HttpHeaders headers = RouteTesterService.parseHeaders("""
				Host: api.example.com
				X-Tenant:acme

				not a header
				""");

		assertThat(headers.getFirst(HttpHeaders.HOST)).isEqualTo("api.example.com");
		assertThat(headers.getFirst("X-Tenant")).isEqualTo("acme");
		assertThat(headers.headerNames()).hasSize(2);
	}

	@Test
	void readsNoHeaderFromABlankBlock() {
		assertThat(RouteTesterService.parseHeaders(null).isEmpty()).isTrue();
		assertThat(RouteTesterService.parseHeaders("   ").isEmpty()).isTrue();
	}

	/**
	 * Builds a route guarded by a path and a method predicate, exactly as the gateway
	 * would combine them for {@code Path=...} and {@code Method=...}.
	 */
	private static Route route(String id, String pathPattern, HttpMethod method) {
		PathRoutePredicateFactory pathFactory = new PathRoutePredicateFactory(new WebFluxProperties());
		PathRoutePredicateFactory.Config pathConfig = new PathRoutePredicateFactory.Config()
			.setPatterns(List.of(pathPattern));
		Predicate<ServerWebExchange> path = pathFactory.apply(pathConfig);

		MethodRoutePredicateFactory methodFactory = new MethodRoutePredicateFactory();
		MethodRoutePredicateFactory.Config methodConfig = new MethodRoutePredicateFactory.Config();
		methodConfig.setMethods(method);
		Predicate<ServerWebExchange> methods = methodFactory.apply(methodConfig);

		return Route.async()
			.id(id)
			.uri("http://" + id)
			.order(0)
			.asyncPredicate(AsyncPredicate.from(path).and(AsyncPredicate.from(methods)))
			.build();
	}

	private static RouteTesterService testerOver(Route... routes) {
		return testerFor(() -> Flux.just(routes));
	}

	@SuppressWarnings("unchecked")
	private static RouteTesterService testerFor(RouteLocator routeLocator) {
		ObjectProvider<RouteLocator> locatorProvider = mock(ObjectProvider.class);
		when(locatorProvider.getIfAvailable()).thenReturn(routeLocator);
		ObjectProvider<RouteDefinitionLocator> locators = mock(ObjectProvider.class);
		when(locators.orderedStream()).thenAnswer((invocation) -> Stream.empty());
		RouteInventoryService inventoryService = new RouteInventoryService(locators,
				mock(ApplicationEventPublisher.class));
		return new RouteTesterService(locatorProvider, inventoryService, null);
	}

}
