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

package ch.nexsol.gateway.routes.core;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Base {@link RouteDefinitionLocator} for sources that are read eagerly and cached, then
 * reloaded on demand (for example when a watched file changes or a periodic poll fires).
 * <p>
 * The cached definitions are served synchronously from memory so that route resolution
 * never blocks on the underlying source. Subclasses supply the actual read through
 * {@link #loadRouteDefinitions()} and trigger reloads through {@link #refresh()}, which
 * updates the cache and publishes a {@link RefreshRoutesEvent} so Spring Cloud Gateway
 * rebuilds its route table.
 */
public abstract class AbstractRefreshableRouteDefinitionLocator implements RouteDefinitionLocator {

	private static final Logger LOG = LoggerFactory.getLogger(AbstractRefreshableRouteDefinitionLocator.class);

	private final ApplicationEventPublisher publisher;

	private final AtomicReference<List<RouteDefinition>> cache = new AtomicReference<>(List.of());

	/**
	 * Creates the locator with the publisher used to notify the gateway of route changes.
	 * @param publisher the application event publisher
	 */
	protected AbstractRefreshableRouteDefinitionLocator(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Serves the last cached snapshot without touching the underlying source.
	 */
	@Override
	public Flux<RouteDefinition> getRouteDefinitions() {
		return Flux.fromIterable(this.cache.get());
	}

	/**
	 * Reads the current route definitions from the underlying source.
	 * @return the freshly read route definitions
	 */
	protected abstract Flux<RouteDefinition> loadRouteDefinitions();

	/**
	 * Reloads the route definitions from the source, replaces the cached snapshot and
	 * publishes a {@link RefreshRoutesEvent}. Errors are logged and swallowed so a
	 * transient source failure keeps the last known routes in place.
	 * @return a completion signal
	 */
	public Mono<Void> refresh() {
		return loadRouteDefinitions().collectList().doOnNext(this::updateCacheAndPublish).onErrorResume((ex) -> {
			LOG.error("Failed to refresh route definitions, keeping the previous snapshot", ex);
			return Mono.empty();
		}).then();
	}

	private void updateCacheAndPublish(List<RouteDefinition> definitions) {
		List<RouteDefinition> loaded = List.copyOf(definitions);
		List<RouteDefinition> previous = this.cache.getAndSet(loaded);
		LOG.debug("Loaded {} route definition(s)", loaded.size());
		// A poll that read the same definitions changes nothing, and publishing anyway
		// makes the gateway rebuild its whole route table — every predicate and every
		// filter of every route — on every single tick.
		if (loaded.equals(previous)) {
			LOG.debug("Route definitions unchanged, not publishing a refresh event");
			return;
		}
		this.publisher.publishEvent(new RefreshRoutesEvent(this));
	}

}
