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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.gateway.event.RefreshRoutesResultEvent;
import org.springframework.context.event.EventListener;

/**
 * Keeps the tools of the server in step with the contracts: each time the gateway has
 * rebuilt its route table, the catalog is read again and the server gains the tools that
 * appeared, loses those that went, and has those that changed replaced. The syncs run one
 * at a time, in order, whatever the pace of the refreshes.
 */
public class HubToolRegistrar implements DisposableBean {

	private static final Logger LOG = LoggerFactory.getLogger(HubToolRegistrar.class);

	private final ApiToolSource source;

	private final ApiToolInvoker invoker;

	private final ToolRegistry registry;

	private final Map<String, Tool> registered = new ConcurrentHashMap<>();

	private final Sinks.Many<Boolean> refreshes = Sinks.many().unicast().onBackpressureBuffer();

	private final Disposable syncs;

	public HubToolRegistrar(ApiToolSource source, ApiToolInvoker invoker, ToolRegistry registry) {
		this.source = source;
		this.invoker = invoker;
		this.registry = registry;
		this.syncs = this.refreshes.asFlux()
			.onBackpressureLatest()
			.concatMap((refresh) -> sync().onErrorResume((ex) -> {
				LOG.warn("The MCP tools of the hub could not be refreshed", ex);
				return Mono.empty();
			}))
			.subscribe();
	}

	@EventListener(condition = "#event.success")
	public void onRoutesRefreshed(RefreshRoutesResultEvent event) {
		this.refreshes.tryEmitNext(Boolean.TRUE);
	}

	/**
	 * Read the catalog and bring the tools of the server in line with it.
	 * @return completion of the sync
	 */
	public Mono<Void> sync() {
		return this.source.load().flatMap(this::apply);
	}

	/**
	 * The names of the tools currently registered.
	 * @return the tool names
	 */
	public Set<String> registeredTools() {
		return Set.copyOf(this.registered.keySet());
	}

	private Mono<Void> apply(List<ApiTool> tools) {
		Map<String, ApiTool> wanted = new LinkedHashMap<>();
		tools.forEach((tool) -> wanted.put(tool.tool().name(), tool));
		Set<String> gone = new LinkedHashSet<>(this.registered.keySet());
		gone.removeAll(wanted.keySet());
		Flux<Void> removals = Flux.fromIterable(gone)
			.concatMap((name) -> this.registry.remove(name).doOnSuccess((v) -> this.registered.remove(name)));
		Flux<Void> additions = Flux.fromIterable(wanted.values())
			.filter((tool) -> !tool.tool().equals(this.registered.get(tool.tool().name())))
			.concatMap(this::replace);
		return removals.thenMany(additions).then().doOnSuccess((v) -> {
			if (!gone.isEmpty() || !tools.isEmpty()) {
				LOG.info("The MCP server serves {} tool(s) of the OpenAPI hub", this.registered.size());
			}
		});
	}

	private Mono<Void> replace(ApiTool tool) {
		String name = tool.tool().name();
		Mono<Void> removal = this.registered.containsKey(name) ? this.registry.remove(name) : Mono.empty();
		AsyncToolSpecification specification = new AsyncToolSpecification(tool.tool(),
				(context, request) -> this.invoker.call(tool.operation(), request.arguments(), context));
		return removal.then(this.registry.add(specification))
			.doOnSuccess((v) -> this.registered.put(name, tool.tool()))
			.onErrorResume((ex) -> {
				LOG.warn("The tool {} could not be registered: {}", name, ex.getMessage());
				return Mono.empty();
			});
	}

	@Override
	public void destroy() {
		this.syncs.dispose();
	}

	/**
	 * The server the tools are added to and removed from.
	 */
	public interface ToolRegistry {

		/**
		 * Add a tool.
		 * @param specification the tool and its handler
		 * @return completion
		 */
		Mono<Void> add(AsyncToolSpecification specification);

		/**
		 * Remove a tool.
		 * @param name the tool name
		 * @return completion
		 */
		Mono<Void> remove(String name);

		/**
		 * The registry over a stateless server.
		 * @param server the server
		 * @return the registry
		 */
		static ToolRegistry of(McpStatelessAsyncServer server) {
			return new ToolRegistry() {
				@Override
				public Mono<Void> add(AsyncToolSpecification specification) {
					return server.addTool(specification);
				}

				@Override
				public Mono<Void> remove(String name) {
					return server.removeTool(name);
				}
			};
		}

	}

}
