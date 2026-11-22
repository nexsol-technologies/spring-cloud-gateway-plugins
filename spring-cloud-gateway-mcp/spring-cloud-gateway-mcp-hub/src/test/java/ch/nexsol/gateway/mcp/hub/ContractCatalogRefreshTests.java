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
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests what a refresh of the catalog costs and what it may never cost. The routes are
 * refreshed on every heartbeat of the registry, changed or not, and each refresh reads
 * the catalog: a contract that did not change must not be parsed again, a contract that
 * did must be, and a service that does not answer must not hold the refresh.
 */
class ContractCatalogRefreshTests {

	private static final URI GATEWAY = URI.create("http://gateway:8181");

	/** The petstore without the operation reading one pet. */
	private static final String SMALLER_PETSTORE = "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"Petstore\","
			+ "\"version\":\"2.0.0\"},\"paths\":{\"/pets\":{\"get\":{\"operationId\":\"listPets\","
			+ "\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";

	private final McpHubProperties properties = new McpHubProperties();

	private String contract = Contracts.PETSTORE;

	private String prefix = "/petstore";

	@Test
	void anUnchangedContractIsNotParsedAgain() {
		ContractCatalog catalog = catalog();

		List<ApiTool> first = catalog.load().block();
		List<ApiTool> second = catalog.load().block();

		assertThat(first).isNotEmpty();
		assertThat(second).hasSameSizeAs(first);
		// The same tools, not tools built anew from the same text.
		for (int i = 0; i < first.size(); i++) {
			assertThat(second.get(i)).isSameAs(first.get(i));
		}
	}

	@Test
	void aContractThatChangedIsParsedAgain() {
		ContractCatalog catalog = catalog();
		assertThat(names(catalog)).containsExactly("petstore__list_pets", "petstore__get_pets_by");

		this.contract = SMALLER_PETSTORE;

		assertThat(names(catalog)).containsExactly("petstore__list_pets");
	}

	@Test
	void aContractThatChangedBackIsParsedAgain() {
		ContractCatalog catalog = catalog();
		names(catalog);
		this.contract = SMALLER_PETSTORE;
		names(catalog);

		this.contract = Contracts.PETSTORE;

		assertThat(names(catalog)).containsExactly("petstore__list_pets", "petstore__get_pets_by");
	}

	@Test
	void anUnchangedContractServedUnderAnotherPrefixIsReadForThatPrefix() {
		ContractCatalog catalog = catalog();
		assertThat(catalog.load().block().get(0).operation().urlTemplate())
			.isEqualTo("http://gateway:8181/petstore/pets");

		this.prefix = "/shop";

		assertThat(catalog.load().block().get(0).operation().urlTemplate()).isEqualTo("http://gateway:8181/shop/pets");
	}

	@Test
	void anUnchangedContractIsReadAgainOnceWritesAreAllowed() {
		ContractCatalog catalog = catalog();
		List<ApiTool> readOnly = catalog.load().block();

		this.properties.setWriteEnabled(true);

		assertThat(catalog.load().block()).hasSizeGreaterThan(readOnly.size());
	}

	@Test
	void aContractReferringToAnotherDocumentIsParsedEveryTime() {
		// What it refers to may change without a character of the contract changing.
		this.contract = "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"Petstore\",\"version\":\"1\"},"
				+ "\"paths\":{\"/pets\":{\"get\":{\"operationId\":\"listPets\",\"responses\":{\"200\":"
				+ "{\"description\":\"ok\",\"content\":{\"application/json\":{\"schema\":"
				+ "{\"$ref\": \"http://localhost:1/schemas.json#/Pet\"}}}}}}}}}";
		ContractCatalog catalog = catalog();

		List<ApiTool> first = catalog.load().block();
		List<ApiTool> second = catalog.load().block();

		assertThat(first).hasSize(1);
		assertThat(second).hasSize(1);
		assertThat(second.get(0)).isNotSameAs(first.get(0));
	}

	@Test
	void aServiceThatDoesNotAnswerDoesNotHoldTheRefresh() {
		WebClient silent = WebClient.builder().exchangeFunction((request) -> Mono.never()).build();
		ContractCatalog catalog = new ContractCatalog(locators(), silent, GATEWAY, this.properties);

		StepVerifier.withVirtualTime(catalog::catalog)
			.thenAwait(this.properties.getTimeout().plusSeconds(1))
			.assertNext((read) -> {
				assertThat(read.tools()).isEmpty();
				// Unread, not gone: the tools it had are kept.
				assertThat(read.unreadServices()).containsExactly("petstore");
			})
			.expectComplete()
			.verify(Duration.ofSeconds(5));
	}

	private List<String> names(ContractCatalog catalog) {
		return catalog.load().block().stream().map((tool) -> tool.tool().name()).toList();
	}

	private ContractCatalog catalog() {
		WebClient client = WebClient.builder()
			.exchangeFunction((request) -> Mono.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body(this.contract)
				.build()))
			.build();
		return new ContractCatalog(locators(), client, GATEWAY, this.properties);
	}

	private ObjectProvider<RouteDefinitionLocator> locators() {
		RouteDefinitionLocator locator = () -> Flux
			.defer(() -> Flux.just(ContractCatalogTests.documentationRoute("petstore", this.prefix)));
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		beanFactory.addBean("locator", locator);
		return beanFactory.getBeanProvider(RouteDefinitionLocator.class);
	}

}
