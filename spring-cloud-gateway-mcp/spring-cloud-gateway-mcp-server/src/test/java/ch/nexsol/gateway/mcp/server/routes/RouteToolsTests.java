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

import ch.nexsol.gateway.mcp.server.routes.RouteTools.FactoryDescription;
import ch.nexsol.gateway.mcp.server.routes.RouteTools.RouteDescription;
import ch.nexsol.gateway.mcp.server.routes.RouteTools.Step;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.CompositeRouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.ResolvableType;

import static org.assertj.core.api.Assertions.assertThat;

class RouteToolsTests {

	@Test
	void listsTheRoutesOfEverySourceNamedAfterIt() {
		RouteDefinitionLocator files = new FilesLocator(route("files-route"));
		RouteDefinitionLocator custom = () -> Flux.just(route("custom-route"));
		StepVerifier.create(tools(List.of(files, custom), List.of(), List.of()).listRoutes()).assertNext((routes) -> {
			assertThat(routes).extracting(RouteDescription::routeId).containsExactly("files-route", "custom-route");
			assertThat(routes).extracting(RouteDescription::source).containsExactly("FilesLocator", "Custom");
		}).verifyComplete();
	}

	@Test
	void leavesOutTheCompositeLocatorThatRepeatsTheSources() {
		RouteDefinitionLocator files = new FilesLocator(route("files-route"));
		RouteDefinitionLocator composite = new CompositeRouteDefinitionLocator(Flux.just(files));
		StepVerifier.create(tools(List.of(composite, files), List.of(), List.of()).listRoutes())
			.assertNext(
					(routes) -> assertThat(routes).extracting(RouteDescription::routeId).containsExactly("files-route"))
			.verifyComplete();
	}

	@Test
	void describesThePredicatesFiltersAndMetadataOfARoute() {
		RouteDefinition books = route("books");
		books.setOrder(3);
		books.setPredicates(List.of(new PredicateDefinition("Path=/books/**")));
		books.setFilters(List.of(new FilterDefinition("StripPrefix=1")));
		books.setMetadata(Map.of("public", true));
		StepVerifier.create(tools(List.of(new FilesLocator(books)), List.of(), List.of()).getRoute("books"))
			.assertNext((route) -> {
				assertThat(route.uri()).isEqualTo("http://books");
				assertThat(route.order()).isEqualTo(3);
				assertThat(route.predicates()).extracting(Step::name).containsExactly("Path");
				assertThat(route.predicates().get(0).args()).containsValue("/books/**");
				assertThat(route.filters()).extracting(Step::name).containsExactly("StripPrefix");
				assertThat(route.filters().get(0).args()).containsValue("1");
				assertThat(route.metadata()).containsEntry("public", true);
				assertThat(route.source()).isEqualTo("FilesLocator");
			})
			.verifyComplete();
	}

	@Test
	void getRouteFailsNamingTheUnknownId() {
		StepVerifier.create(tools(List.of(new FilesLocator(route("books"))), List.of(), List.of()).getRoute("nowhere"))
			.expectErrorSatisfies(
					(ex) -> assertThat(ex).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nowhere"))
			.verify();
	}

	@Test
	void aSourceThatCannotBeReadFailsTheToolNamingTheSource() {
		StepVerifier.create(tools(List.of(new BrokenLocator()), List.of(), List.of()).listRoutes())
			.expectErrorSatisfies((ex) -> assertThat(ex).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("BrokenLocator")
				.hasMessageContaining("database down"))
			.verify();
	}

	@Test
	void listsTheFactoriesWithTheFieldsOfTheirShorthand() {
		RouteTools tools = tools(List.of(), List.of(new StripPrefixGatewayFilterFactory()),
				List.of(new MethodRoutePredicateFactory()));
		StepVerifier.create(tools.listFilterFactories())
			.assertNext((factories) -> assertThat(factories)
				.containsExactly(new FactoryDescription("StripPrefix", List.of("parts"))))
			.verifyComplete();
		StepVerifier.create(tools.listPredicateFactories())
			.assertNext((factories) -> assertThat(factories)
				.containsExactly(new FactoryDescription("Method", List.of("methods"))))
			.verifyComplete();
	}

	private static RouteDefinition route(String id) {
		RouteDefinition definition = new RouteDefinition();
		definition.setId(id);
		definition.setUri(URI.create("http://" + id.replace("-route", "")));
		return definition;
	}

	private static RouteTools tools(List<RouteDefinitionLocator> locators, List<GatewayFilterFactory<?>> filters,
			List<RoutePredicateFactory<?>> predicates) {
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		int index = 0;
		for (Object bean : List.of(locators, filters, predicates)) {
			for (Object element : (List<?>) bean) {
				beanFactory.addBean("bean" + (index++), element);
			}
		}
		return new RouteTools(beanFactory.getBeanProvider(RouteDefinitionLocator.class),
				beanFactory.getBeanProvider(ResolvableType.forClass(GatewayFilterFactory.class)),
				beanFactory.getBeanProvider(ResolvableType.forClass(RoutePredicateFactory.class)));
	}

	static class FilesLocator implements RouteDefinitionLocator {

		private final List<RouteDefinition> definitions;

		FilesLocator(RouteDefinition... definitions) {
			this.definitions = List.of(definitions);
		}

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			return Flux.fromIterable(this.definitions);
		}

	}

	static class BrokenLocator implements RouteDefinitionLocator {

		@Override
		public Flux<RouteDefinition> getRouteDefinitions() {
			return Flux.error(new IllegalStateException("database down"));
		}

	}

}
