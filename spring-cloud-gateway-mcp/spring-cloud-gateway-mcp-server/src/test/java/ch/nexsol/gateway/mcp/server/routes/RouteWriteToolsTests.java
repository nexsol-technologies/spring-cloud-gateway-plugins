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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.mcp.server.WriteGuard;
import ch.nexsol.gateway.mcp.server.routes.RouteTools.Step;
import ch.nexsol.gateway.mcp.server.routes.RouteWriteTools.RouteSpec;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;

import static org.assertj.core.api.Assertions.assertThat;

class RouteWriteToolsTests {

	private final List<RouteDefinition> saved = new ArrayList<>();

	private final List<String> deleted = new ArrayList<>();

	private final List<Object> events = new ArrayList<>();

	private final RouteDefinitionWriter writer = new RouteDefinitionWriter() {
		@Override
		public Mono<Void> save(Mono<RouteDefinition> route) {
			return route.doOnNext(RouteWriteToolsTests.this.saved::add).then();
		}

		@Override
		public Mono<Void> delete(Mono<String> routeId) {
			return routeId.doOnNext(RouteWriteToolsTests.this.deleted::add).then();
		}
	};

	private final RouteWriteTools tools = new RouteWriteTools(this.writer, this.events::add, new WriteGuard(true));

	private final RouteSpec books = new RouteSpec("books", "http://books:8080", 5,
			List.of(new Step("Path", Map.of("_genkey_0", "/books/**"))),
			List.of(new Step("StripPrefix", Map.of("_genkey_0", "1"))), Map.of("public", true));

	@Test
	void savesTheRouteAndRebuildsTheTableOnceConfirmed() {
		StepVerifier.create(this.tools.saveRoute(this.books, null))
			.assertNext((result) -> assertThat(text(result)).contains("save the route 'books'"))
			.verifyComplete();
		assertThat(this.saved).isEmpty();
		assertThat(this.events).isEmpty();

		StepVerifier.create(this.tools.saveRoute(this.books, true))
			.assertNext((result) -> assertThat(text(result)).contains("Route 'books' saved"))
			.verifyComplete();
		assertThat(this.saved).hasSize(1);
		RouteDefinition definition = this.saved.get(0);
		assertThat(definition.getId()).isEqualTo("books");
		assertThat(definition.getUri()).hasToString("http://books:8080");
		assertThat(definition.getOrder()).isEqualTo(5);
		assertThat(definition.getPredicates()).hasSize(1);
		assertThat(definition.getPredicates().get(0).getName()).isEqualTo("Path");
		assertThat(definition.getPredicates().get(0).getArgs()).containsEntry("_genkey_0", "/books/**");
		assertThat(definition.getFilters().get(0).getName()).isEqualTo("StripPrefix");
		assertThat(definition.getMetadata()).containsEntry("public", true);
		assertThat(this.events).singleElement().isInstanceOf(RefreshRoutesEvent.class);
	}

	@Test
	void deletesTheRouteAndRebuildsTheTableOnceConfirmed() {
		StepVerifier.create(this.tools.deleteRoute("books", true))
			.assertNext((result) -> assertThat(text(result)).contains("Route 'books' deleted"))
			.verifyComplete();
		assertThat(this.deleted).containsExactly("books");
		assertThat(this.events).hasSize(1);
	}

	@Test
	void refreshesTheTableOnceConfirmed() {
		StepVerifier.create(this.tools.refreshRoutes(true))
			.assertNext((result) -> assertThat(text(result)).contains("rebuilt"))
			.verifyComplete();
		assertThat(this.events).singleElement().isInstanceOf(RefreshRoutesEvent.class);
	}

	@Test
	void refusesARouteWithoutAnIdOrATarget() {
		RouteSpec nowhere = new RouteSpec("books", null, null, null, null, null);
		StepVerifier.create(this.tools.saveRoute(nowhere, true)).assertNext((result) -> {
			assertThat(result.isError()).isTrue();
			assertThat(text(result)).contains("routeId and a uri");
		}).verifyComplete();
		assertThat(this.saved).isEmpty();
	}

	private static String text(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

}
