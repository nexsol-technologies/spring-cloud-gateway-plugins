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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.swagger.v3.oas.models.PathItem;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

/**
 * What becomes of the tools already served when the catalog is read again and part of it
 * does not answer: the registrar over the contract catalog, each service a host whose
 * contract a stub serves or refuses.
 */
class HubCatalogRetentionTests {

	private static final URI GATEWAY = URI.create("http://gateway:8181");

	private final List<String> journal = new CopyOnWriteArrayList<>();

	private final Map<String, String> contracts = new ConcurrentHashMap<>();

	private final Set<String> down = ConcurrentHashMap.newKeySet();

	private final List<RouteDefinition> routes = new CopyOnWriteArrayList<>();

	private final AtomicBoolean sourceDown = new AtomicBoolean();

	private final HubToolRegistrar.ToolRegistry registry = new HubToolRegistrar.ToolRegistry() {
		@Override
		public Mono<Void> add(Tool tool,
				BiFunction<McpTransportContext, CallToolRequest, Mono<CallToolResult>> handler) {
			return Mono.fromRunnable(() -> HubCatalogRetentionTests.this.journal.add("+" + tool.name()));
		}

		@Override
		public Mono<Void> remove(String name) {
			return Mono.fromRunnable(() -> HubCatalogRetentionTests.this.journal.add("-" + name));
		}
	};

	private final HubToolRegistrar registrar = registrar();

	@Test
	void keepsTheToolsOfAServiceThatStoppedAnsweringAndRemovesWhatTheOthersDropped() {
		document("a", "/a", "one", "two");
		document("b", "/b", "one");
		this.registrar.sync().block();
		assertThat(this.journal).containsExactlyInAnyOrder("+a__one", "+a__two", "+b__one");

		// b no longer answers: its tool is missing from the catalog, not gone. The one a
		// dropped is gone, a having answered.
		this.journal.clear();
		this.down.add("b");
		this.contracts.put("a", contract("one"));
		this.registrar.sync().block();
		assertThat(this.journal).containsExactly("-a__two");
		assertThat(this.registrar.registeredTools()).containsExactlyInAnyOrder("a__one", "b__one");
	}

	@Test
	void keepsThoseToolsHoweverManyTimesTheServiceFailsToAnswer() {
		document("a", "/a", "one");
		document("b", "/b", "one");
		this.registrar.sync().block();
		this.down.add("b");
		this.registrar.sync().block();
		this.journal.clear();

		// A contract that never reads again must not hold back what the others drop.
		this.contracts.put("a", contract());
		this.registrar.sync().block();
		assertThat(this.journal).containsExactly("-a__one");
		assertThat(this.registrar.registeredTools()).containsExactly("b__one");
	}

	@Test
	void removesTheToolsOfAServiceOnceItAnswersWithoutThem() {
		document("b", "/b", "one");
		this.registrar.sync().block();
		this.down.add("b");
		this.registrar.sync().block();
		this.journal.clear();

		this.down.remove("b");
		this.contracts.put("b", contract());
		this.registrar.sync().block();
		assertThat(this.journal).containsExactly("-b__one");
	}

	@Test
	void removesTheToolsOfAServiceThatIsNoLongerDocumented() {
		document("a", "/a", "one");
		document("b", "/b", "one");
		this.registrar.sync().block();
		this.journal.clear();

		this.down.add("b");
		this.routes.removeIf((route) -> route.getId().endsWith("-b"));
		this.registrar.sync().block();
		assertThat(this.journal).containsExactly("-b__one");
	}

	@Test
	void keepsEveryToolWhileTheRouteSourceCannotBeRead() {
		document("a", "/a", "one");
		document("b", "/b", "one");
		this.registrar.sync().block();
		this.journal.clear();

		// Which services are documented is not even known.
		this.sourceDown.set(true);
		this.registrar.sync().block();
		assertThat(this.journal).isEmpty();
		assertThat(this.registrar.registeredTools()).containsExactlyInAnyOrder("a__one", "b__one");
	}

	@Test
	void replacesAToolWhoseServiceMovedUnderAnotherPrefix() {
		document("a", "/a", "one");
		this.registrar.sync().block();
		this.journal.clear();

		// Same contract, same schema: only the URL the calls are sent to changes.
		this.routes.clear();
		document("a", "/moved", "one");
		this.registrar.sync().block();
		assertThat(this.journal).containsExactly("-a__one", "+a__one");
	}

	@Test
	void replacesAToolWhoseOperationChangedUnderTheSameSchema() {
		List<ApiTool> tools = new CopyOnWriteArrayList<>(List.of(tool("http://gateway/a/one")));
		HubToolRegistrar listed = new HubToolRegistrar(() -> Mono.just(List.copyOf(tools)),
				new ApiToolInvoker(WebClient.create(), Duration.ofSeconds(1), true), this.registry);
		listed.sync().block();
		this.journal.clear();

		tools.set(0, tool("http://gateway/moved/one"));
		listed.sync().block();
		assertThat(this.journal).containsExactly("-a__one", "+a__one");
	}

	@Test
	void removesWhatASourceOfTheApplicationNoLongerLists() {
		// A source that only lists its tools is taken to have read everything.
		List<ApiTool> tools = new CopyOnWriteArrayList<>(List.of(tool("http://gateway/a/one")));
		HubToolRegistrar listed = new HubToolRegistrar(() -> Mono.just(List.copyOf(tools)),
				new ApiToolInvoker(WebClient.create(), Duration.ofSeconds(1), true), this.registry);
		listed.sync().block();
		tools.clear();
		listed.sync().block();
		assertThat(this.journal).containsExactly("+a__one", "-a__one");
	}

	private HubToolRegistrar registrar() {
		WebClient client = WebClient.builder().exchangeFunction((request) -> {
			String service = request.url().getHost();
			if (this.down.contains(service)) {
				return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
			}
			return Mono.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body(this.contracts.get(service))
				.build());
		}).build();
		RouteDefinitionLocator locator = () -> this.sourceDown.get()
				? Flux.error(new IllegalStateException("route source down")) : Flux.fromIterable(this.routes);
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		beanFactory.addBean("locator", locator);
		ContractCatalog catalog = new ContractCatalog(beanFactory.getBeanProvider(RouteDefinitionLocator.class), client,
				GATEWAY, new McpHubProperties());
		return new HubToolRegistrar(catalog, new ApiToolInvoker(WebClient.create(), Duration.ofSeconds(1), true),
				this.registry);
	}

	/**
	 * Documents a service: its documentation route, as the hub declares it, and the
	 * contract its host answers with.
	 */
	private void document(String service, String prefix, String... operations) {
		RouteDefinition route = new RouteDefinition();
		route.setId("openapi-docs-discovery-" + service);
		route.setUri(URI.create("http://" + service + ":8080"));
		route.setMetadata(Map.of("name", service));
		route.setPredicates(List.of(new PredicateDefinition("Path=/v3/api-docs/" + service)));
		route.setFilters(List.of(new FilterDefinition("RewritePath=/v3/api-docs/" + service + ", /v3/api-docs"),
				new FilterDefinition("OpenapiModifyResponseBody=" + prefix)));
		this.routes.add(route);
		this.contracts.put(service, contract(operations));
	}

	private static String contract(String... operations) {
		String paths = Arrays.stream(operations)
			.map((operation) -> "\"/" + operation + "\":{\"get\":{\"operationId\":\"" + operation
					+ "\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}")
			.collect(Collectors.joining(","));
		return "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{" + paths + "}}";
	}

	private static ApiTool tool(String url) {
		Tool tool = Tool.builder("a__one")
			.description("first")
			.inputSchema(Map.of("type", "object", "properties", Map.of()))
			.build();
		return new ApiTool(tool, new ApiOperation("a", PathItem.HttpMethod.GET, url, Map.of(), null));
	}

}
