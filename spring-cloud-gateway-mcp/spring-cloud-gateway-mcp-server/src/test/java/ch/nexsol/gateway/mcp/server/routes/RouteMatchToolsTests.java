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

package ch.nexsol.gateway.mcp.server.routes;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import ch.nexsol.gateway.commons.routes.RouteTester;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class RouteMatchToolsTests {

	@Test
	void namesTheRouteTheRequestWouldTakeAndReportsEveryPredicate() {
		RouteLocator table = () -> Flux.just(route("books", "/books/**"), route("orders", "/orders/**"));
		RouteDefinitionLocator source = new FilesRouteDefinitionLocator(definition("books"));
		RouteTester tester = new RouteTester(provider(RouteLocator.class, table),
				() -> RouteMatchTools.origins(provider(RouteDefinitionLocator.class, source)), null);
		StepVerifier.create(new RouteMatchTools(tester).matchRequest(null, "/books/42", null)).assertNext((report) -> {
			assertThat(report.matchedRouteId()).isEqualTo("books");
			assertThat(report.routes()).hasSize(2);
			assertThat(report.routes().get(0).matched()).isTrue();
			assertThat(report.routes().get(0).source()).isEqualTo("Files");
			assertThat(report.routes().get(0).filters()).containsExactly("StripPrefix=1");
			assertThat(report.routes().get(1).matched()).isFalse();
			assertThat(report.routes().get(1).predicates()).singleElement()
				.satisfies((outcome) -> assertThat(outcome.matched()).isFalse());
		}).verifyComplete();
	}

	@Test
	void describesAFilterTheWayItWasDeclared() {
		assertThat(RouteMatchTools.describe(new FilterDefinition("StripPrefix=1"))).isEqualTo("StripPrefix=1");
		FilterDefinition named = new FilterDefinition();
		named.setName("Retry");
		named.setArgs(Map.of("retries", "3"));
		assertThat(RouteMatchTools.describe(named)).isEqualTo("Retry(retries=3)");
		FilterDefinition bare = new FilterDefinition();
		bare.setName("Maintenance");
		assertThat(RouteMatchTools.describe(bare)).isEqualTo("Maintenance");
	}

	@Test
	void readsTheOriginsOfEverySourceAndKeepsTheFirstDeclarationOfAnId() {
		RouteDefinitionLocator files = new FilesRouteDefinitionLocator(definition("books"));
		RouteDefinitionLocator custom = () -> Flux.just(definition("books"), definition("orders"));
		StepVerifier.create(RouteMatchTools.origins(provider(RouteDefinitionLocator.class, files, custom)))
			.assertNext((origins) -> {
				assertThat(origins).containsOnlyKeys("books", "orders");
				assertThat(origins.get("books").source()).isEqualTo("Files");
				assertThat(origins.get("orders").source()).isEqualTo("Custom");
			})
			.verifyComplete();
	}

	private static Route route(String id, String pattern) {
		PathRoutePredicateFactory factory = new PathRoutePredicateFactory(new WebFluxProperties());
		Predicate<ServerWebExchange> path = factory
			.apply(new PathRoutePredicateFactory.Config().setPatterns(List.of(pattern)));
		return Route.async().id(id).uri("http://" + id).order(0).asyncPredicate(AsyncPredicate.from(path)).build();
	}

	private static RouteDefinition definition(String id) {
		RouteDefinition definition = new RouteDefinition();
		definition.setId(id);
		definition.setUri(URI.create("http://" + id));
		definition.setFilters(List.of(new FilterDefinition("StripPrefix=1")));
		return definition;
	}

	@SafeVarargs
	private static <T> ObjectProvider<T> provider(Class<T> type, T... beans) {
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		for (int i = 0; i < beans.length; i++) {
			beanFactory.addBean(type.getSimpleName() + i, beans[i]);
		}
		return beanFactory.getBeanProvider(type);
	}

	static class FilesRouteDefinitionLocator implements RouteDefinitionLocator {

		private final List<RouteDefinition> definitions;

		FilesRouteDefinitionLocator(RouteDefinition... definitions) {
			this.definitions = List.of(definitions);
		}

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			return Flux.fromIterable(this.definitions);
		}

	}

}
