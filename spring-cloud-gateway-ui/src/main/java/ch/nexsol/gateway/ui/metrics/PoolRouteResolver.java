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

package ch.nexsol.gateway.ui.metrics;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ch.nexsol.gateway.ui.routes.RouteInventoryService;
import ch.nexsol.gateway.ui.routes.RouteView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;

/**
 * Names the routes served by a connection pool, from the downstream address the pool
 * counters carry.
 * <p>
 * A pool is keyed on {@code host:port} &mdash; behind Docker Swarm or Kubernetes, a
 * container identity that says nothing to a reader. The route that produced it is known
 * two ways, and both are needed:
 * <ul>
 * <li>a route with a literal URI carries its own authority, so the address is read
 * straight from the route table &mdash; no registry involved. The port is often left
 * implicit there while the pool always has one, so the default port of the scheme is
 * filled in;</li>
 * <li>a load-balanced route ({@code lb://SERVICE-X}) does not know which instance was
 * called. Only the registry maps an address back to a service, and the route table maps
 * the service back to route ids.</li>
 * </ul>
 * <p>
 * The registry is read for the addresses the route table could not name, and only for
 * those. What stays unresolved is left out rather than guessed: a pool towards an
 * instance that has just been deregistered, or towards a downstream called outside any
 * route, has no route id to show, and an empty cell states that.
 */
public class PoolRouteResolver {

	private static final Logger LOG = LoggerFactory.getLogger(PoolRouteResolver.class);

	/** Scheme of a route delegating its target to the load balancer. */
	private static final String LOAD_BALANCED = "lb";

	/** Ports a URI is allowed to leave out, which the connection pool never does. */
	private static final Map<String, Integer> DEFAULT_PORTS = Map.of("http", 80, "https", 443, "ws", 80, "wss", 443);

	/**
	 * Time the registry is given to answer. It is read on every poll of the view, so a
	 * slow registry must cost an unnamed pool rather than a page that hangs.
	 */
	private static final Duration REGISTRY_TIMEOUT = Duration.ofSeconds(2);

	private final RouteInventoryService inventory;

	private final ObjectProvider<ReactiveDiscoveryClient> discoveryClient;

	/**
	 * Creates the resolver over the route inventory and the (optional) service registry.
	 * @param inventory the inventory the route table is read from
	 * @param discoveryClient the provider over the registry, absent in a gateway with no
	 * load-balanced route
	 */
	public PoolRouteResolver(RouteInventoryService inventory, ObjectProvider<ReactiveDiscoveryClient> discoveryClient) {
		this.inventory = inventory;
		this.discoveryClient = discoveryClient;
	}

	/**
	 * Names the routes behind each of the given downstream addresses.
	 * @param addresses the {@code host:port} the connection pools report
	 * @return the route ids per address, several when a downstream is the target of
	 * several routes, falling back to the service id for an address the registry knows
	 * but no route points at. An address that could not be named is absent from the map.
	 */
	public Mono<Map<String, List<String>>> routesByAddress(Collection<String> addresses) {
		if (addresses.isEmpty()) {
			return Mono.just(Map.of());
		}
		Set<String> wanted = Set.copyOf(addresses);
		return this.inventory.routes().flatMap((routes) -> resolve(routes, wanted)).onErrorResume((ex) -> {
			LOG.debug("Could not name the routes behind the connection pools: {}", ex.getMessage());
			return Mono.just(Map.of());
		});
	}

	private Mono<Map<String, List<String>>> resolve(List<RouteView> routes, Set<String> wanted) {
		Map<String, List<String>> named = new LinkedHashMap<>();
		Map<String, List<String>> byService = new LinkedHashMap<>();
		for (RouteView route : routes) {
			URI uri = uri(route.uri());
			if (uri == null || uri.getHost() == null) {
				continue;
			}
			if (LOAD_BALANCED.equalsIgnoreCase(uri.getScheme())) {
				byService.computeIfAbsent(serviceKey(uri.getHost()), (key) -> new ArrayList<>()).add(route.routeId());
			}
			else {
				String address = address(uri);
				if (address != null && wanted.contains(address)) {
					named.computeIfAbsent(address, (key) -> new ArrayList<>()).add(route.routeId());
				}
			}
		}
		ReactiveDiscoveryClient client = this.discoveryClient.getIfAvailable();
		if (client == null || named.keySet().containsAll(wanted)) {
			return Mono.just(named);
		}
		return fromRegistry(client, byService, wanted, named);
	}

	private Mono<Map<String, List<String>>> fromRegistry(ReactiveDiscoveryClient client,
			Map<String, List<String>> byService, Set<String> wanted, Map<String, List<String>> named) {
		return client.getServices()
			.flatMap((service) -> client.getInstances(service).map((instance) -> entry(service, instance)))
			.filter((entry) -> wanted.contains(entry.address()) && !named.containsKey(entry.address()))
			.collectList()
			.timeout(REGISTRY_TIMEOUT)
			.map((entries) -> {
				for (RegisteredAddress entry : entries) {
					List<String> ids = byService.get(serviceKey(entry.serviceId()));
					named.putIfAbsent(entry.address(), (ids != null) ? ids : List.of(entry.serviceId()));
				}
				return named;
			})
			.onErrorResume((ex) -> {
				LOG.debug("Could not read the service registry to name the connection pools: {}", ex.getMessage());
				return Mono.just(named);
			});
	}

	private static RegisteredAddress entry(String serviceId, ServiceInstance instance) {
		return new RegisteredAddress(serviceId, instance.getHost() + ":" + instance.getPort());
	}

	/** Service ids are matched regardless of case: registries and routes disagree. */
	private static String serviceKey(String serviceId) {
		return serviceId.toUpperCase(Locale.ROOT);
	}

	private static URI uri(String uri) {
		try {
			return (uri != null) ? new URI(uri) : null;
		}
		catch (URISyntaxException ex) {
			return null;
		}
	}

	/**
	 * Reads the address a literal URI points at, filling in the default port of the
	 * scheme. A URI with neither port nor known scheme is left out: guessing there would
	 * name a pool after a route it may not serve.
	 */
	private static String address(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getHost() + ":" + uri.getPort();
		}
		Integer port = (uri.getScheme() != null) ? DEFAULT_PORTS.get(uri.getScheme().toLowerCase(Locale.ROOT)) : null;
		return (port != null) ? uri.getHost() + ":" + port : null;
	}

	/** One address the registry holds, with the service it was registered under. */
	private record RegisteredAddress(String serviceId, String address) {
	}

}
