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

package ch.nexsol.gateway.routes.security;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesResultEvent;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;

/**
 * Security exchange matcher that resolves the gateway route targeted by the current
 * request and matches when that route is flagged as public through its metadata.
 * <p>
 * Spring Security runs before Spring Cloud Gateway resolves the matching route, so this
 * matcher replays the route predicates against the exchange (exactly as the gateway does
 * later) to determine the target route up front. A route is considered public when its
 * metadata carries a truthy {@value #PUBLIC_METADATA_KEY} entry.
 * <p>
 * A matching route is not always the one serving the request. The dispatcher asks every
 * {@link HandlerMapping} in order and the routes come after the controllers, the router
 * functions and the actuator endpoints: a request one of those answers never reaches the
 * route, however well its predicate matches. Such a request is not matched here, or a
 * public route declared over {@code /**} would open every endpoint the gateway serves
 * itself.
 * <p>
 * This matcher is asked for every request, ahead of any other chain, so it replays as
 * little as it can: the routes behind the last public one can only ever answer "not
 * public", and are left out. That list is only trusted by an instance that is told when
 * the routes change &mdash; a bean, which receives the refresh events of the gateway. An
 * instance nobody tells anything reads the route table on every request.
 */
public class PublicRouteMatcher implements ServerWebExchangeMatcher, SmartApplicationListener {

	/**
	 * Route metadata key flagging a route as publicly accessible, hence exempt from
	 * authentication.
	 */
	public static final String PUBLIC_METADATA_KEY = "public";

	private final RouteLocator routeLocator;

	private final ObjectProvider<HandlerMapping> handlerMappings;

	private volatile List<HandlerMapping> localMappings;

	private final AtomicLong routesVersion = new AtomicLong();

	private volatile boolean toldOfRouteChanges;

	private volatile List<Route> candidates;

	/**
	 * Creates the matcher over the routes alone, for a caller that has no endpoint of its
	 * own to keep closed.
	 * @param routeLocator the locator providing the (order-sorted) gateway routes
	 */
	public PublicRouteMatcher(RouteLocator routeLocator) {
		this(routeLocator, null);
	}

	/**
	 * Creates the matcher.
	 * @param routeLocator the locator providing the (order-sorted) gateway routes
	 * @param handlerMappings the handler mappings of the application, among which the
	 * ones dispatched ahead of the routes are looked up
	 */
	public PublicRouteMatcher(RouteLocator routeLocator, ObjectProvider<HandlerMapping> handlerMappings) {
		this.routeLocator = routeLocator;
		this.handlerMappings = handlerMappings;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Mono<MatchResult> matches(ServerWebExchange exchange) {
		return candidateRoutes().concatMap((route) -> matchingRoute(route, exchange))
			.next()
			.filter(PublicRouteMatcher::isPublic)
			.filterWhen((route) -> servedLocally(exchange).map((local) -> !local))
			.flatMap((route) -> MatchResult.match())
			.switchIfEmpty(MatchResult.notMatch());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		// Both, because they bracket the rebuild of the route table: the first says the
		// sources changed, the second that the table the locator serves caught up.
		return RefreshRoutesEvent.class.isAssignableFrom(eventType)
				|| RefreshRoutesResultEvent.class.isAssignableFrom(eventType);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public synchronized void onApplicationEvent(ApplicationEvent event) {
		this.routesVersion.incrementAndGet();
		this.candidates = null;
		this.toldOfRouteChanges = true;
	}

	private Flux<Route> candidateRoutes() {
		if (!this.toldOfRouteChanges) {
			return this.routeLocator.getRoutes();
		}
		List<Route> known = this.candidates;
		if (known != null) {
			return Flux.fromIterable(known);
		}
		long version = this.routesVersion.get();
		return this.routeLocator.getRoutes()
			.collectList()
			.map(PublicRouteMatcher::upToLastPublic)
			.doOnNext((routes) -> remember(version, routes))
			.flatMapMany(Flux::fromIterable);
	}

	private synchronized void remember(long version, List<Route> routes) {
		// Read before a refresh and finished after it, this list is already out of date.
		if (version == this.routesVersion.get()) {
			this.candidates = routes;
		}
	}

	private static List<Route> upToLastPublic(List<Route> routes) {
		int last = -1;
		for (int i = 0; i < routes.size(); i++) {
			if (isPublic(routes.get(i))) {
				last = i;
			}
		}
		return List.copyOf(routes.subList(0, last + 1));
	}

	private static Mono<Route> matchingRoute(Route route, ServerWebExchange exchange) {
		// A broken predicate must not fail the whole chain: treat it as non-matching.
		return Mono.from(route.getPredicate().apply(exchange))
			.filter(Boolean::booleanValue)
			.map((matched) -> route)
			.onErrorResume((ex) -> Mono.empty());
	}

	private Mono<Boolean> servedLocally(ServerWebExchange exchange) {
		// A mapping that refuses the request — wrong method, unsupported media type —
		// signals an error rather than no handler: the path is its own all the same.
		return Flux.fromIterable(localMappings())
			.concatMap((mapping) -> mapping.getHandler(exchange))
			.hasElements()
			.onErrorReturn(true);
	}

	private List<HandlerMapping> localMappings() {
		List<HandlerMapping> mappings = this.localMappings;
		if (mappings == null) {
			// Resolved on the first request: the mappings are not all there yet while the
			// security chain holding this matcher is being built.
			mappings = (this.handlerMappings != null) ? this.handlerMappings.orderedStream()
				.takeWhile((mapping) -> !(mapping instanceof RoutePredicateHandlerMapping))
				.toList() : List.of();
			this.localMappings = mappings;
		}
		return mappings;
	}

	private static boolean isPublic(Route route) {
		Map<String, Object> metadata = route.getMetadata();
		Object flag = (metadata != null) ? metadata.get(PUBLIC_METADATA_KEY) : null;
		if (flag instanceof Boolean bool) {
			return bool;
		}
		return (flag != null) && Boolean.parseBoolean(flag.toString());
	}

}
