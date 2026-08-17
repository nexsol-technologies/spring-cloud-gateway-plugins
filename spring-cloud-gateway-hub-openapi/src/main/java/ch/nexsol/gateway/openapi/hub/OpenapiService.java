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
import org.springframework.http.HttpStatusCode;
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
	 * The last outcome reported for an instance that yields no document for a reason
	 * worth a warning. A route refresh happens on every discovery heartbeat, and a
	 * service that refuses its document refuses it on every one of them: without this the
	 * log would carry the same line every few seconds.
	 */
	private final Map<String, Reported> reported = new ConcurrentHashMap<>();

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
		// stops at the first path that settles the question, instead of firing all the
		// probes in parallel. An unreachable instance is the only answer worth stopping
		// on before the paths run out: it answers the same way whatever the path, each
		// attempt costing another full timeout. A refusal, on the other hand, is decided
		// per path: a rule permitting /v3/api-docs/** refuses the suffixed paths and
		// serves the plain one, and each attempt costs an answer, not a timeout.
		return Flux.fromIterable(paths)
			.concatMap((path) -> probe(uri, path, routeDefinition))
			.takeUntil(Probe::settles)
			.collectList()
			.flatMap((probes) -> toDiscover(uri, probes, routeId(routeDefinition)));
	}

	private Mono<OpenapiDiscover> toDiscover(URI uri, List<Probe> probes, String routeId) {
		Probe last = probes.get(probes.size() - 1);
		if (last.found()) {
			cache(uri, last.path());
			this.reported.remove(uri.toString());
			return Mono.just(new OpenapiDiscover(last.path(), last.routeDefinition()));
		}
		// Only a service that answered "not there" tells us it has no document, and it
		// has
		// to have answered that on every candidate path: one refused path leaves the
		// question open, whatever the paths probed after it answered. A refused or
		// unreachable instance says nothing, and caching that would keep it out of the
		// hub
		// until the entry expires, long after it came back.
		Probe unresolved = probes.stream()
			.filter((probe) -> probe.outcome() != Probe.Outcome.ABSENT)
			.findFirst()
			.orElse(null);
		if (unresolved == null) {
			cache(uri, null);
			this.reported.remove(uri.toString());
			LOG.debug("No OpenAPI document under {} for route {}; it is left out of the hub", uri, routeId);
			return Mono.empty();
		}
		report(uri, unresolved, routeId);
		return Mono.empty();
	}

	/**
	 * Says once, rather than on every heartbeat, that a discovered service is not in the
	 * hub and why. A service without a document is a normal thing and stays silent; a
	 * service that has one and will not hand it over is a configuration to fix.
	 */
	private void report(URI uri, Probe probe, String routeId) {
		Instant now = Instant.now();
		this.reported.values().removeIf((entry) -> entry.expiresAt().isBefore(now));
		Reported previous = this.reported.put(uri.toString(),
				new Reported(probe.outcome(), now.plus(reportInterval())));
		if (previous != null && previous.outcome() == probe.outcome()) {
			LOG.debug("Route {} is still left out of the OpenAPI hub: {} {}", routeId, uri + probe.path(),
					probe.reason());
			return;
		}
		if (probe.outcome() == Probe.Outcome.DENIED) {
			LOG.warn("Route {} is left out of the OpenAPI hub: {} answered {}. Its document is protected, and the hub "
					+ "reads it anonymously.", routeId, uri + probe.path(), probe.reason());
		}
		else {
			LOG.warn("Route {} is left out of the OpenAPI hub: {} could not be reached ({})", routeId,
					uri + probe.path(), probe.reason());
		}
	}

	/**
	 * How long the same reason stays out of the log. The probes themselves are throttled
	 * by the cache, so this follows it, and falls back on a value of its own when the
	 * cache is disabled and every refresh probes again.
	 */
	private Duration reportInterval() {
		return this.cacheTtl.isPositive() ? this.cacheTtl : Duration.ofMinutes(5);
	}

	private Mono<Probe> probe(URI uri, String path, RouteDefinition routeDefinition) {
		return this.webClient.get().uri(uri + path).exchangeToMono((response) -> {
			HttpStatusCode status = response.statusCode();
			LOG.debug("url {} {} for route {} : {}", uri + path, status.equals(HttpStatus.OK) ? "found" : "not found",
					routeDefinition, status);
			// The body is released, never buffered: only the path the document was found
			// at is used downstream, and holding every document of every service in
			// memory
			// on every route refresh is what brings a large registry down.
			return response.releaseBody().thenReturn(Probe.answered(path, status, routeDefinition));
		}).onErrorResume((ex) -> {
			LOG.debug("url {} unreachable for route {} : {}", uri + path, routeDefinition, ex.getMessage());
			return Mono.just(Probe.failed(path, ex, routeDefinition));
		});
	}

	private static String routeId(RouteDefinition routeDefinition) {
		return (routeDefinition != null && StringUtils.hasText(routeDefinition.getId())) ? routeDefinition.getId()
				: "unknown";
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
	 * Outcome of a single probe. Three ways of yielding no document, which the hub must
	 * not confuse: a service that answered "not there" has no document and is remembered
	 * as such, while one that refused to hand it over and one that could not be reached
	 * have said nothing about it and are reported instead.
	 *
	 * @param path the probed path
	 * @param outcome what the probe found
	 * @param reason what the service answered, or why it could not be reached
	 * @param routeDefinition the route definition the probe was run for
	 */
	private record Probe(String path, Outcome outcome, String reason, RouteDefinition routeDefinition) {

		private enum Outcome {

			FOUND, ABSENT, DENIED, FAILED

		}

		static Probe answered(String path, HttpStatusCode status, RouteDefinition routeDefinition) {
			Outcome outcome;
			if (status.equals(HttpStatus.OK)) {
				outcome = Outcome.FOUND;
			}
			else if (status.value() == HttpStatus.UNAUTHORIZED.value()
					|| status.value() == HttpStatus.FORBIDDEN.value()) {
				outcome = Outcome.DENIED;
			}
			else {
				outcome = Outcome.ABSENT;
			}
			return new Probe(path, outcome, status.toString(), routeDefinition);
		}

		static Probe failed(String path, Throwable error, RouteDefinition routeDefinition) {
			return new Probe(path, Outcome.FAILED, String.valueOf(error.getMessage()), routeDefinition);
		}

		boolean found() {
			return this.outcome == Outcome.FOUND;
		}

		/**
		 * Whether this probe settles the question, leaving the remaining candidate paths
		 * nothing to add. A found document does, and so does an unreachable instance,
		 * which fails to answer the same way whatever the path and costs another full
		 * timeout per attempt. A refusal does not: authorization is granted path by path,
		 * and the next candidate is one immediate answer away.
		 * @return {@code true} for a document found or an instance out of reach
		 */
		boolean settles() {
			return this.outcome == Outcome.FOUND || this.outcome == Outcome.FAILED;
		}

	}

	/**
	 * The last reason a service instance was reported as left out of the hub, and how
	 * long that reason stays out of the log.
	 *
	 * @param outcome the reason it was left out
	 * @param expiresAt the instant the same reason is worth logging again
	 */
	private record Reported(Probe.Outcome outcome, Instant expiresAt) {
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
