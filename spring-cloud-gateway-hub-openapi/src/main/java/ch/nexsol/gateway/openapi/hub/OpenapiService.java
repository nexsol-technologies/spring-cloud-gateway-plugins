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

package ch.nexsol.gateway.openapi.hub;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ch.nexsol.gateway.openapi.HubOpenapiProperties;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Discovers the OpenAPI documentation endpoint exposed by a discovered service instance
 * by probing the well-known SpringDoc paths, and remembers what it found so the next
 * route refresh does not probe the whole registry again.
 */
public class OpenapiService implements DisposableBean {

	private static final Logger LOG = LoggerFactory.getLogger(OpenapiService.class);

	/**
	 * Service instance metadata key that a discovered service may declare to advertise
	 * the path of its OpenAPI document, bypassing the probing of the well-known SpringDoc
	 * paths.
	 */
	public static final String METADATA_SERVICE_INSTANCE_OPENAPI_PATH_KEY = "openapi_path";

	private static final List<String> DEFAULT_PATHS = List.of("/v3/api-docs.json", "/v3/api-docs.yaml", "/v3/api-docs");

	private final ReactiveDiscoveryClient discoveryClient;

	private final WebClient webClient;

	private final ConnectionProvider connectionProvider;

	private final Duration cacheTtl;

	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

	/**
	 * Creates a new service backed by the given discovery client, probing with the
	 * default settings.
	 * @param discoveryClient the reactive discovery client used to resolve service
	 * instances
	 */
	public OpenapiService(ReactiveDiscoveryClient discoveryClient) {
		this(discoveryClient, new HubOpenapiProperties.Discovery());
	}

	/**
	 * Creates a new service backed by the given discovery client.
	 * @param discoveryClient the reactive discovery client used to resolve service
	 * instances
	 * @param properties the settings bounding the probes
	 */
	public OpenapiService(ReactiveDiscoveryClient discoveryClient, HubOpenapiProperties.Discovery properties) {
		this.discoveryClient = discoveryClient;
		this.cacheTtl = properties.getCacheTtl();
		// A pool of its own: probing must never take the connections the gateway proxies
		// its traffic on, and the default pool is sized for a handful of connections.
		this.connectionProvider = ConnectionProvider.builder("hub-openapi-discovery")
			.maxConnections(properties.getMaxConnections())
			.pendingAcquireTimeout(properties.getTimeout())
			.build();
		HttpClient httpClient = HttpClient.create(this.connectionProvider)
			.responseTimeout(properties.getTimeout())
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.getTimeout().toMillis()));
		this.webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build();
	}

	// Visible for testing: drives the probes through a stubbed exchange, without a
	// network stack nor a connection pool to dispose of.
	OpenapiService(ReactiveDiscoveryClient discoveryClient, WebClient webClient, Duration cacheTtl) {
		this.discoveryClient = discoveryClient;
		this.webClient = webClient;
		this.cacheTtl = cacheTtl;
		this.connectionProvider = null;
	}

	/**
	 * Discovers the OpenAPI document for the first available instance of the given route.
	 * <p>
	 * Completes empty when the route has no instance or no OpenAPI document is found.
	 * @param routeId the discovery service id whose instances are probed
	 * @param routeDefinition the route definition the discovered document is attached to
	 * @return a {@link Mono} emitting the discovered OpenAPI document, or empty if none
	 */
	public Mono<OpenapiDiscover> discoverOpenapiUrl(String routeId, RouteDefinition routeDefinition) {
		// next() (instead of last()) picks the first instance and completes empty when a
		// route has no instance, rather than emitting NoSuchElementException.
		return this.discoveryClient.getInstances(routeId)
			.next()
			.flatMap((si) -> discoverOpenapiUrl(si, routeDefinition));
	}

	/**
	 * Releases the connection pool dedicated to the probes.
	 */
	@Override
	public void destroy() {
		if (this.connectionProvider != null) {
			this.connectionProvider.dispose();
		}
	}

	private Mono<OpenapiDiscover> discoverOpenapiUrl(ServiceInstance serviceInstance, RouteDefinition routeDefinition) {
		URI uri = serviceInstance.getUri();

		CacheEntry cached = lookUpCache(uri);
		if (cached != null) {
			return (cached.path() != null) ? Mono.just(new OpenapiDiscover(cached.path(), routeDefinition))
					: Mono.empty();
		}

		String declaredPath = serviceInstance.getMetadata()
			.getOrDefault(METADATA_SERVICE_INSTANCE_OPENAPI_PATH_KEY, "");
		List<String> paths = StringUtils.hasText(declaredPath) ? List.of(declaredPath) : DEFAULT_PATHS;

		// concatMap preserves the .json -> .yaml -> plain preference order and takeUntil
		// stops at the first match, instead of firing all the probes in parallel.
		// A probe that could not reach the instance stops the sequence too: the remaining
		// paths fail the same way, each costing another full timeout, so an unreachable
		// service costs one timeout rather than one per candidate path.
		return Flux.fromIterable(paths)
			.concatMap((path) -> probe(uri, path, routeDefinition))
			.takeUntil((probe) -> probe.found() || !probe.answered())
			.collectList()
			.flatMap((probes) -> toDiscover(uri, probes, routeDefinition));
	}

	private Mono<OpenapiDiscover> toDiscover(URI uri, List<Probe> probes, RouteDefinition routeDefinition) {
		Probe last = probes.get(probes.size() - 1);
		if (last.found()) {
			cache(uri, last.path());
			return Mono.just(new OpenapiDiscover(last.path(), routeDefinition));
		}
		// Only a service that answered tells us it has no document. A probe that could
		// not reach it says nothing, and caching that would keep the service out of the
		// hub until the entry expires, long after it came back.
		if (probes.stream().allMatch(Probe::answered)) {
			cache(uri, null);
		}
		return Mono.empty();
	}

	private Mono<Probe> probe(URI uri, String path, RouteDefinition routeDefinition) {
		return this.webClient.get().uri(uri + path).exchangeToMono((response) -> {
			boolean found = response.statusCode().equals(HttpStatus.OK);
			LOG.debug("url {} {} for route {} : {}", uri + path, found ? "found" : "not found", routeDefinition,
					response.statusCode());
			// The body is released, never buffered: only the path the document was found
			// at is used downstream, and holding every document of every service in
			// memory
			// on every route refresh is what brings a large registry down.
			return response.releaseBody().thenReturn(found ? Probe.found(path) : Probe.absent(path));
		}).onErrorResume((ex) -> {
			LOG.debug("url {} unreachable for route {} : {}", uri + path, routeDefinition, ex.getMessage());
			return Mono.just(Probe.failed(path));
		});
	}

	private CacheEntry lookUpCache(URI uri) {
		if (!this.cacheTtl.isPositive()) {
			return null;
		}
		CacheEntry entry = this.cache.get(uri.toString());
		if (entry == null) {
			return null;
		}
		if (entry.expiresAt().isBefore(Instant.now())) {
			this.cache.remove(uri.toString(), entry);
			return null;
		}
		return entry;
	}

	private void cache(URI uri, String path) {
		if (!this.cacheTtl.isPositive()) {
			return;
		}
		Instant now = Instant.now();
		// Evict what expired along the way, so the instances that left the registry do
		// not
		// pile up.
		this.cache.values().removeIf((entry) -> entry.expiresAt().isBefore(now));
		this.cache.put(uri.toString(), new CacheEntry(path, now.plus(this.cacheTtl)));
	}

	/**
	 * Result of an OpenAPI discovery.
	 *
	 * @param path the path at which the OpenAPI document was found
	 * @param routeDefinition the route definition the document belongs to
	 */
	public record OpenapiDiscover(String path, RouteDefinition routeDefinition) {
	}

	/**
	 * Outcome of a single probe. A service that answered "not there" and a service that
	 * could not be reached both yield no document, but only the former is worth
	 * remembering.
	 *
	 * @param path the probed path
	 * @param outcome what the probe found
	 */
	private record Probe(String path, Outcome outcome) {

		private enum Outcome {

			FOUND, ABSENT, FAILED

		}

		static Probe found(String path) {
			return new Probe(path, Outcome.FOUND);
		}

		static Probe absent(String path) {
			return new Probe(path, Outcome.ABSENT);
		}

		static Probe failed(String path) {
			return new Probe(path, Outcome.FAILED);
		}

		boolean found() {
			return this.outcome == Outcome.FOUND;
		}

		boolean answered() {
			return this.outcome != Outcome.FAILED;
		}

	}

	/**
	 * What the last probing of a service instance found.
	 *
	 * @param path the path the document was found at, or {@code null} when the instance
	 * confirmed it has none
	 * @param expiresAt the instant the entry stops being trusted
	 */
	private record CacheEntry(String path, Instant expiresAt) {
	}

}
