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

package ch.nexsol.gateway.mcp.server.resources;

import java.net.URI;

import ch.nexsol.gateway.mcp.server.routes.RouteTools;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.ResolvableType;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayResourcesTests {

	private final GatewayResources resources = resources();

	@Test
	void servesTheRoutesAsJson() {
		StepVerifier.create(this.resources.routes())
			.assertNext(
					(json) -> assertThat(json).contains("\"routeId\":\"books\"").contains("\"unreadableSources\":[]"))
			.verifyComplete();
	}

	@Test
	void servesOneRouteByIdAndFailsForAnUnknownOne() {
		StepVerifier.create(this.resources.route("books"))
			.assertNext((json) -> assertThat(json).contains("\"uri\":\"http://books\""))
			.verifyComplete();
		StepVerifier.create(this.resources.route("nowhere")).expectError(IllegalArgumentException.class).verify();
	}

	@Test
	void servesTheFactories() {
		StepVerifier.create(this.resources.factories())
			.assertNext((json) -> assertThat(json).contains("\"filters\"")
				.contains("StripPrefix")
				.contains("\"predicates\":[]"))
			.verifyComplete();
	}

	private static GatewayResources resources() {
		RouteDefinition books = new RouteDefinition();
		books.setId("books");
		books.setUri(URI.create("http://books"));
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		beanFactory.addBean("locator", (RouteDefinitionLocator) () -> Flux.just(books));
		beanFactory.addBean("stripPrefix", new StripPrefixGatewayFilterFactory());
		RouteTools tools = new RouteTools(beanFactory.getBeanProvider(RouteDefinitionLocator.class),
				beanFactory.getBeanProvider(ResolvableType.forClass(GatewayFilterFactory.class)),
				beanFactory.getBeanProvider(ResolvableType.forClass(RoutePredicateFactory.class)));
		return new GatewayResources(tools);
	}

}
