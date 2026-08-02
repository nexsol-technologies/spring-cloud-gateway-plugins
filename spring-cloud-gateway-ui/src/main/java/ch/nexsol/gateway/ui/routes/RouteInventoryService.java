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

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.CompositeRouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.support.NameUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.util.ClassUtils;

/**
 * Lists the route definitions the gateway actually resolves, attributing each one to the
 * {@link RouteDefinitionLocator} it was read from.
 * <p>
 * Every locator bean is queried individually rather than through the
 * {@link CompositeRouteDefinitionLocator} aggregate, which is what makes the origin of a
 * route visible: properties, database, files, OpenAPI contracts, Config Server or any
 * third-party source contributed to the context.
 * <p>
 * The resulting snapshot is cached and served while it is refreshed, the same way the
 * gateway itself only queries its locators on a {@link RefreshRoutesEvent}: a locator is
 * free to reach the network on every call, and the views built on this inventory are
 * rendered on every page load. A {@link RefreshRoutesEvent} therefore marks the snapshot
 * stale instead of dropping it, and the read it triggers runs in the background.
 */
public class RouteInventoryService implements ApplicationListener<RefreshRoutesEvent> {

	private static final Logger LOG = LoggerFactory.getLogger(RouteInventoryService.class);

	/**
	 * Time a single source is given to answer. A locator reaching the network (service
	 * discovery probes, remote documents) must not hold the page: past this delay it is
	 * dropped from the snapshot, as a source that fails to be read is.
	 */
	private static final Duration SOURCE_TIMEOUT = Duration.ofSeconds(5);

	/** Locator class-name suffixes stripped when deriving the displayed source name. */
	private static final List<String> LOCATOR_SUFFIXES = List.of("RouteDefinitionLocator", "RouteDefinitionRepository",
			"RouteDefinitionSource");

	/**
	 * Splits a camel-case class name into words, so {@code ConfigServer} reads as two.
	 */
	private static final Pattern CAMEL_CASE_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

	/** Prefix the gateway gives an argument declared positionally, without a name. */
	private static final String GENERATED_NAME_PREFIX = NameUtils.GENERATED_NAME_PREFIX;

	private final ObjectProvider<RouteDefinitionLocator> locators;

	private final ApplicationEventPublisher publisher;

	private final AtomicReference<Mono<List<RouteView>>> snapshot = new AtomicReference<>();

	/**
	 * Last inventory resolved in full. It is what every view is served from while a
	 * refresh runs, so a page render never waits on a read of every source.
	 */
	private final AtomicReference<List<RouteView>> lastKnown = new AtomicReference<>();

	/**
	 * Set when the gateway signalled a route change. The next reader consumes it to
	 * trigger a single background refresh, so a burst of refresh events costs one read.
	 */
	private final AtomicBoolean stale = new AtomicBoolean();

	/**
	 * Creates the service over every route definition source in the context.
	 * @param locators the provider over every {@link RouteDefinitionLocator} bean
	 * @param publisher the publisher used to ask the gateway to rebuild its route table
	 */
	public RouteInventoryService(ObjectProvider<RouteDefinitionLocator> locators, ApplicationEventPublisher publisher) {
		this.locators = locators;
		this.publisher = publisher;
	}

	/**
	 * Collects the route definitions of every source, in locator order, flagging the ids
	 * declared by more than one source.
	 * <p>
	 * Only the very first reader waits on the sources. Afterwards the last resolved
	 * inventory is served straight away and a {@link RefreshRoutesEvent} merely marks it
	 * stale, so the read that follows runs in the background rather than in the middle of
	 * a page render. A locator backed by service discovery re-reads the registry, and
	 * probes it, on every call; with a few hundred services that read outlasts any
	 * acceptable page load, and refresh events arrive on every discovery heartbeat, so a
	 * snapshot dropped on each event would leave every view paying for a full read.
	 * @return the resolved routes, source by source
	 */
	public Mono<List<RouteView>> routes() {
		return Mono.defer(() -> {
			List<RouteView> known = this.lastKnown.get();
			if (known == null) {
				return read();
			}
			if (this.stale.compareAndSet(true, false)) {
				revalidate();
			}
			return Mono.just(known);
		});
	}

	/**
	 * Re-reads every source, dropping the cached snapshot first, and waits for the
	 * result. This is what the <em>Refresh view</em> action asks for, as opposed to
	 * merely displaying a view.
	 * @return the freshly resolved routes, source by source
	 */
	public Mono<List<RouteView>> refreshedRoutes() {
		return Mono.defer(() -> {
			this.snapshot.set(null);
			this.stale.set(false);
			return read();
		});
	}

	/**
	 * Marks the inventory stale whenever the gateway rebuilds its route table, so the
	 * next reader triggers a background read and later views see the routes the gateway
	 * just resolved. The current inventory is kept in place: dropping it would make the
	 * next page render wait on every source.
	 * @param event the route refresh event
	 */
	@Override
	public void onApplicationEvent(RefreshRoutesEvent event) {
		this.snapshot.set(null);
		this.stale.set(true);
	}

	/**
	 * Subscribes to a read whose result later views are served from, without holding the
	 * caller. Failures are logged only: the previous inventory stays in place.
	 */
	private void revalidate() {
		read().subscribe((refreshed) -> LOG.debug("Refreshed the route inventory in the background: {} route(s)",
				refreshed.size()), (ex) -> LOG.warn("Background route inventory refresh failed", ex));
	}

	/**
	 * Reads every source, sharing a read already in flight rather than starting a second
	 * one: a view opened while a refresh runs must not double the load on the sources.
	 * @return the resolved routes
	 */
	private Mono<List<RouteView>> read() {
		return this.snapshot.updateAndGet((cached) -> (cached != null) ? cached : readSources());
	}

	private Mono<List<RouteView>> readSources() {
		List<RouteDefinitionLocator> sources = this.locators.orderedStream()
			.filter((locator) -> !(locator instanceof CompositeRouteDefinitionLocator))
			.toList();
		return Flux.fromIterable(sources)
			// Sources are read concurrently but emitted in locator order: a slow source
			// costs its own latency, not that of every source queued before it.
			.flatMapSequential(this::readSource)
			.collectList()
			.map(RouteInventoryService::flagDuplicates)
			.doOnNext(this.lastKnown::set)
			.cache();
	}

	/**
	 * Asks the gateway to rebuild its route table from the current definitions by
	 * publishing a {@link RefreshRoutesEvent}, exactly as the gateway actuator endpoint
	 * does.
	 */
	public void reload() {
		LOG.debug("Publishing RefreshRoutesEvent on behalf of the gateway UI");
		this.publisher.publishEvent(new RefreshRoutesEvent(this));
	}

	/**
	 * Derives the displayed source name from the locator class, so a source contributed
	 * by any plugin reads correctly without this module knowing about it.
	 * @param locator the locator to name
	 * @return the human-readable source name
	 */
	static String sourceName(RouteDefinitionLocator locator) {
		String name = ClassUtils.getUserClass(locator).getSimpleName();
		if (name.isEmpty() || name.contains("$$Lambda")) {
			// A locator declared as a lambda has no name worth showing.
			return "Custom";
		}
		for (String suffix : LOCATOR_SUFFIXES) {
			if (name.length() > suffix.length() && name.endsWith(suffix)) {
				name = name.substring(0, name.length() - suffix.length());
				break;
			}
		}
		return CAMEL_CASE_BOUNDARY.matcher(name).replaceAll(" ");
	}

	/**
	 * Renders a predicate or filter definition the way it is declared.
	 * <p>
	 * Arguments given positionally, as the YAML shortcut syntax does, read back as that
	 * shortcut: {@code Path=/api/**}. Named arguments are rendered as a call, so the name
	 * of the element stays apart from the names of its arguments, as in
	 * {@code Path(patterns=/api/**)}.
	 * @param name the predicate or filter name
	 * @param args the declared arguments
	 * @return the representation shown in the UI
	 */
	static String describe(String name, Map<String, String> args) {
		if (args == null || args.isEmpty()) {
			return name;
		}
		boolean positional = args.keySet().stream().allMatch((key) -> key.startsWith(GENERATED_NAME_PREFIX));
		String rendered = args.entrySet()
			.stream()
			.map((arg) -> arg.getKey().startsWith(GENERATED_NAME_PREFIX) ? arg.getValue()
					: arg.getKey() + "=" + arg.getValue())
			.collect(Collectors.joining(", "));
		return positional ? name + "=" + rendered : name + "(" + rendered + ")";
	}

	private Flux<RouteView> readSource(RouteDefinitionLocator locator) {
		String source = sourceName(locator);
		// Collected before the timeout is applied, so the delay bounds the whole read of
		// the source rather than the gap between two of its routes.
		return locator.getRouteDefinitions()
			.map((definition) -> toView(definition, source))
			.collectList()
			.timeout(SOURCE_TIMEOUT)
			.onErrorResume((ex) -> {
				LOG.warn("Route definition source {} could not be read", source, ex);
				return Mono.just(List.of());
			})
			.flatMapMany(Flux::fromIterable);
	}

	private static RouteView toView(RouteDefinition definition, String source) {
		return new RouteView(definition.getId(), (definition.getUri() != null) ? definition.getUri().toString() : null,
				definition.getOrder(),
				definition.getPredicates().stream().map((p) -> describe(p.getName(), p.getArgs())).toList(),
				definition.getFilters().stream().map((f) -> describe(f.getName(), f.getArgs())).toList(), source,
				false);
	}

	private static List<RouteView> flagDuplicates(List<RouteView> routes) {
		Set<String> seen = new HashSet<>();
		Set<String> duplicated = new HashSet<>();
		for (RouteView route : routes) {
			if (route.routeId() != null && !seen.add(route.routeId())) {
				duplicated.add(route.routeId());
			}
		}
		if (duplicated.isEmpty()) {
			return routes;
		}
		return routes.stream()
			.map((route) -> duplicated.contains(route.routeId()) ? new RouteView(route.routeId(), route.uri(),
					route.order(), route.predicates(), route.filters(), route.source(), true) : route)
			.toList();
	}

}
