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

package ch.nexsol.gateway.mcp.hub;

import java.net.URI;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.mcp.hub.ContractCatalog.DocumentedService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class ContractCatalogTests {

	private static final URI GATEWAY = URI.create("http://gateway:8181");

	@Test
	void readsTheServiceItsContractAndItsPrefixFromADocumentationRoute() {
		DocumentedService service = ContractCatalog.service(documentationRoute("petstore", "/petstore"), GATEWAY);
		assertThat(service.name()).isEqualTo("petstore");
		assertThat(service.contractUrl()).hasToString("http://svc:8080/v3/api-docs");
		assertThat(service.base()).hasToString("http://gateway:8181/petstore");
	}

	@Test
	void servesAContractAdvertisedAtTheRootUnderTheGatewayItself() {
		assertThat(ContractCatalog.service(documentationRoute("petstore", "/"), GATEWAY).base())
			.hasToString("http://gateway:8181");
	}

	@Test
	void leavesTheOtherRoutesAlone() {
		RouteDefinition books = new RouteDefinition();
		books.setId("books");
		books.setUri(URI.create("http://books"));
		assertThat(ContractCatalog.service(books, GATEWAY)).isNull();
	}

	@Test
	void readsTheToolsOfEveryDocumentedServiceAndSkipsAContractItCannotRead() {
		WebClient client = WebClient.builder().exchangeFunction((request) -> {
			if (request.url().getHost().equals("svc")) {
				return Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.body(Contracts.PETSTORE)
					.build());
			}
			return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
		}).build();
		RouteDefinitionLocator locator = () -> Flux.just(documentationRoute("petstore", "/petstore"),
				documentationRoute("broken", "/broken"));
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		beanFactory.addBean("locator", locator);
		ContractCatalog catalog = new ContractCatalog(beanFactory.getBeanProvider(RouteDefinitionLocator.class), client,
				GATEWAY, new McpHubProperties());
		StepVerifier.create(catalog.load()).assertNext((tools) -> {
			assertThat(tools).extracting((tool) -> tool.tool().name())
				.containsExactly("petstore__list_pets", "petstore__get_pets_by");
			assertThat(tools.get(0).operation().urlTemplate()).isEqualTo("http://gateway:8181/petstore/pets");
		}).verifyComplete();
	}

	static RouteDefinition documentationRoute(String name, String prefix) {
		RouteDefinition route = new RouteDefinition();
		route.setId("openapi-docs-discovery-" + name);
		route.setUri(URI.create(("broken".equals(name) ? "http://down:8080" : "http://svc:8080")));
		route.setMetadata(Map.of("name", name));
		route.setPredicates(List.of(new PredicateDefinition("Path=/v3/api-docs/" + name)));
		route.setFilters(List.of(new FilterDefinition("RewritePath=/v3/api-docs/" + name + ", /v3/api-docs"),
				new FilterDefinition("OpenapiModifyResponseBody=" + prefix),
				new FilterDefinition("RemoveRequestHeader=Cookie")));
		return route;
	}

}
