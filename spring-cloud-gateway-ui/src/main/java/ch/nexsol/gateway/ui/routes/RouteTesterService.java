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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.support.HasConfig;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Evaluates a described request against the gateway route table without calling any
 * downstream service.
 * <p>
 * The routes are read from the {@link RouteLocator}, so the verdict comes from the very
 * predicates the gateway would apply, in the very order it applies them. Each route is
 * additionally broken down predicate by predicate, which is what turns a bare "no match"
 * into "the path matched but the method did not".
 */
public class RouteTesterService {

	/** Host used when the tested request carries no {@code Host} header. */
	static final String DEFAULT_HOST = "localhost";

	/**
	 * Reported when the gateway itself is switched off, leaving no route table to test.
	 */
	static final String NO_ROUTE_TABLE = "The gateway route table is not available";

	private final ObjectProvider<RouteLocator> routeLocator;

	private final RouteInventoryService inventoryService;

	private final ApplicationContext applicationContext;

	/**
	 * Creates the service over the gateway route table.
	 * <p>
	 * The locator is resolved lazily and optionally: when the gateway is disabled there
	 * is no route table, and the tester reports that instead of failing.
	 * @param routeLocator the provider over the locator exposing the effective route
	 * table
	 * @param inventoryService the service resolving the definition behind a route
	 * @param applicationContext the context exposed to the predicates through the
	 * exchange
	 */
	public RouteTesterService(ObjectProvider<RouteLocator> routeLocator, RouteInventoryService inventoryService,
			ApplicationContext applicationContext) {
		this.routeLocator = routeLocator;
		this.inventoryService = inventoryService;
		this.applicationContext = applicationContext;
	}

	/**
	 * Tests the described request against every route.
	 * @param method the HTTP method, e.g. {@code GET}
	 * @param path the request path, optionally with a query string, e.g.
	 * {@code /api/v1?x=1}
	 * @param rawHeaders the request headers, one {@code Name: value} pair per line
	 * @return the per-route verdicts and the route the gateway would pick
	 */
	public Mono<RouteTestReport> test(String method, String path, String rawHeaders) {
		HttpMethod httpMethod = HttpMethod.valueOf(StringUtils.hasText(method) ? method.trim().toUpperCase() : "GET");
		HttpHeaders headers = parseHeaders(rawHeaders);
		URI uri;
		try {
			uri = buildUri(path, headers.getFirst(HttpHeaders.HOST));
		}
		catch (RuntimeException ex) {
			return Mono.just(new RouteTestReport(httpMethod.name(), path, null, List.of(), message(ex)));
		}
		RouteLocator locator = this.routeLocator.getIfAvailable();
		if (locator == null) {
			return Mono.just(new RouteTestReport(httpMethod.name(), uri.toString(), null, List.of(), NO_ROUTE_TABLE));
		}
		return this.inventoryService.routes()
			.map(RouteTesterService::definitionsById)
			.flatMap((definitions) -> report(locator, httpMethod, uri, headers, definitions));
	}

	/**
	 * Splits the raw header block into headers, ignoring blank and malformed lines so a
	 * half-typed header never fails the whole test.
	 * @param rawHeaders the headers, one {@code Name: value} pair per line
	 * @return the parsed headers
	 */
	static HttpHeaders parseHeaders(String rawHeaders) {
		HttpHeaders headers = new HttpHeaders();
		if (!StringUtils.hasText(rawHeaders)) {
			return headers;
		}
		for (String line : rawHeaders.split("\\R")) {
			int separator = line.indexOf(':');
			if (separator <= 0) {
				continue;
			}
			String name = line.substring(0, separator).trim();
			String value = line.substring(separator + 1).trim();
			if (StringUtils.hasText(name)) {
				headers.add(name, value);
			}
		}
		return headers;
	}

	private Mono<RouteTestReport> report(RouteLocator locator, HttpMethod method, URI uri, HttpHeaders headers,
			Map<String, RouteView> definitions) {
		return locator.getRoutes()
			.concatMap((route) -> evaluate(route, method, uri, headers, definitions))
			.collectList()
			.map((matches) -> new RouteTestReport(method.name(), uri.toString(), firstMatch(matches), matches, null));
	}

	private Mono<RouteMatch> evaluate(Route route, HttpMethod method, URI uri, HttpHeaders headers,
			Map<String, RouteView> definitions) {
		// A fresh exchange per route: predicates may cache state in the attributes,
		// which no other route must observe.
		ServerWebExchange exchange = new SyntheticServerWebExchange(
				new SyntheticServerHttpRequest(method, uri, headers), this.applicationContext);
		exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_PREDICATE_ROUTE_ATTR, route.getId());
		RouteView definition = definitions.get(route.getId());
		return Flux.fromIterable(leaves(route))
			.concatMap((leaf) -> outcome(leaf, exchange))
			.collectList()
			.flatMap((outcomes) -> verdict(route, exchange).map((matched) -> new RouteMatch(route.getId(),
					String.valueOf(route.getUri()), route.getOrder(), (definition != null) ? definition.source() : null,
					matched, null, outcomes, (definition != null) ? definition.filters() : List.of())))
			.onErrorResume((ex) -> Mono.just(new RouteMatch(route.getId(), String.valueOf(route.getUri()),
					route.getOrder(), (definition != null) ? definition.source() : null, false, message(ex), List.of(),
					(definition != null) ? definition.filters() : List.of())));
	}

	/**
	 * Applies the route predicate as a whole. This is the authoritative verdict: it
	 * honours the {@code and}, {@code or} and {@code negate} combinations, which the
	 * per-predicate breakdown cannot express on its own.
	 */
	private static Mono<Boolean> verdict(Route route, ServerWebExchange exchange) {
		return Mono.defer(() -> Mono.from(route.getPredicate().apply(exchange)));
	}

	/**
	 * Collects the individual predicates a route was built from, using the visitor the
	 * gateway itself uses to walk a combined predicate.
	 */
	private static List<HasConfig> leaves(Route route) {
		List<HasConfig> collected = new ArrayList<>();
		route.getPredicate().accept(collected::add);
		return collected;
	}

	@SuppressWarnings("unchecked")
	private static Mono<PredicateOutcome> outcome(HasConfig leaf, ServerWebExchange exchange) {
		String description = String.valueOf(leaf);
		try {
			if (leaf instanceof AsyncPredicate) {
				return Mono.from(((AsyncPredicate<ServerWebExchange>) leaf).apply(exchange))
					.map((matched) -> new PredicateOutcome(description, matched, null))
					.onErrorResume((ex) -> Mono.just(new PredicateOutcome(description, false, message(ex))));
			}
			if (leaf instanceof Predicate) {
				return Mono.just(
						new PredicateOutcome(description, ((Predicate<ServerWebExchange>) leaf).test(exchange), null));
			}
		}
		catch (RuntimeException ex) {
			return Mono.just(new PredicateOutcome(description, false, message(ex)));
		}
		return Mono.just(new PredicateOutcome(description, false, "This predicate cannot be evaluated in isolation"));
	}

	private static URI buildUri(String path, String host) {
		String requested = StringUtils.hasText(path) ? path.trim() : "/";
		return UriComponentsBuilder.fromUriString(requested.startsWith("/") ? requested : "/" + requested)
			.scheme("http")
			.host(StringUtils.hasText(host) ? host : DEFAULT_HOST)
			.encode()
			.build()
			.toUri();
	}

	private static Map<String, RouteView> definitionsById(List<RouteView> routes) {
		Map<String, RouteView> byId = new LinkedHashMap<>();
		for (RouteView route : routes) {
			byId.putIfAbsent(route.routeId(), route);
		}
		return byId;
	}

	private static String firstMatch(List<RouteMatch> matches) {
		return matches.stream().filter(RouteMatch::matched).map(RouteMatch::routeId).findFirst().orElse(null);
	}

	private static String message(Throwable ex) {
		return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
	}

}
