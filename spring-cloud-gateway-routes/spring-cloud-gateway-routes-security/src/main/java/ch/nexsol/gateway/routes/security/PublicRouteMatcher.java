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

import java.util.Map;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;

/**
 * Security exchange matcher that resolves the gateway route targeted by the current
 * request and matches when that route is flagged as public through its metadata.
 * <p>
 * Spring Security runs before Spring Cloud Gateway resolves the matching route, so this
 * matcher replays the route predicates against the exchange (exactly as the gateway does
 * later) to determine the target route up front. A route is considered public when its
 * metadata carries a truthy {@value #PUBLIC_METADATA_KEY} entry.
 */
public class PublicRouteMatcher implements ServerWebExchangeMatcher {

	/**
	 * Route metadata key flagging a route as publicly accessible, hence exempt from
	 * authentication.
	 */
	public static final String PUBLIC_METADATA_KEY = "public";

	private final RouteLocator routeLocator;

	/**
	 * Creates the matcher.
	 * @param routeLocator the locator providing the (order-sorted) gateway routes
	 */
	public PublicRouteMatcher(RouteLocator routeLocator) {
		this.routeLocator = routeLocator;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Mono<MatchResult> matches(ServerWebExchange exchange) {
		return this.routeLocator.getRoutes()
			.concatMap((route) -> matchingRoute(route, exchange))
			.next()
			.filter(PublicRouteMatcher::isPublic)
			.flatMap((route) -> MatchResult.match())
			.switchIfEmpty(MatchResult.notMatch());
	}

	private static Mono<Route> matchingRoute(Route route, ServerWebExchange exchange) {
		// A broken predicate must not fail the whole chain: treat it as non-matching.
		return Mono.from(route.getPredicate().apply(exchange))
			.filter(Boolean::booleanValue)
			.map((matched) -> route)
			.onErrorResume((ex) -> Mono.empty());
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
