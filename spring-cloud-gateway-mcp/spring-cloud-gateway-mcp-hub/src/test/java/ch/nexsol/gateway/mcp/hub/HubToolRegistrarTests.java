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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.swagger.v3.oas.models.PathItem;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class HubToolRegistrarTests {

	private final List<String> journal = new ArrayList<>();

	private final HubToolRegistrar.ToolRegistry registry = new HubToolRegistrar.ToolRegistry() {
		@Override
		public Mono<Void> add(Tool tool,
				BiFunction<McpTransportContext, CallToolRequest, Mono<CallToolResult>> handler) {
			return Mono.fromRunnable(() -> HubToolRegistrarTests.this.journal.add("+" + tool.name()));
		}

		@Override
		public Mono<Void> remove(String name) {
			return Mono.fromRunnable(() -> HubToolRegistrarTests.this.journal.add("-" + name));
		}
	};

	private final AtomicReference<List<ApiTool>> catalog = new AtomicReference<>(List.of());

	private final HubToolRegistrar registrar = new HubToolRegistrar(() -> Mono.just(this.catalog.get()),
			new ApiToolInvoker(WebClient.create(), Duration.ofSeconds(1), true), this.registry);

	@Test
	void addsTheToolsThatAppearedRemovesThoseThatWentAndReplacesThoseThatChanged() {
		this.catalog.set(List.of(tool("a__one", "first"), tool("a__two", "second")));
		this.registrar.sync().block();
		assertThat(this.journal).containsExactly("+a__one", "+a__two");
		assertThat(this.registrar.registeredTools()).containsExactlyInAnyOrder("a__one", "a__two");

		this.journal.clear();
		this.catalog.set(List.of(tool("a__one", "first"), tool("a__three", "third")));
		this.registrar.sync().block();
		assertThat(this.journal).containsExactly("-a__two", "+a__three");

		this.journal.clear();
		this.catalog.set(List.of(tool("a__one", "renamed"), tool("a__three", "third")));
		this.registrar.sync().block();
		// Same name, another description: the server holds the tool it was given, so the
		// old one is removed before the new one is added.
		assertThat(this.journal).containsExactly("-a__one", "+a__one");
		assertThat(this.registrar.registeredTools()).containsExactlyInAnyOrder("a__one", "a__three");
	}

	@Test
	void doesNothingWhenNothingChanged() {
		this.catalog.set(List.of(tool("a__one", "first")));
		this.registrar.sync().block();
		this.journal.clear();
		this.registrar.sync().block();
		assertThat(this.journal).isEmpty();
	}

	private static ApiTool tool(String name, String description) {
		Tool tool = Tool.builder(name)
			.description(description)
			.inputSchema(Map.of("type", "object", "properties", Map.of()))
			.build();
		return new ApiTool(tool,
				new ApiOperation("a", PathItem.HttpMethod.GET, "http://gateway/" + name, Map.of(), null));
	}

}
