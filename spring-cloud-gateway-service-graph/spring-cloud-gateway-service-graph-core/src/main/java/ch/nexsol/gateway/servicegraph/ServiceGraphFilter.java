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

package ch.nexsol.gateway.servicegraph;

import java.net.URI;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Counts one call per routed exchange, from the caller to the service the route targets,
 * through the route it took. This is what the whole graph is built on, whichever source
 * reports it.
 * <p>
 * A {@link GlobalFilter} rather than a {@code WebFilter}, because an edge needs a route
 * at its far end: a request the gateway answered itself, or that matched no route, has no
 * second endpoint to draw.
 */
public class ServiceGraphFilter implements GlobalFilter, Ordered {

	/** Name of the counter carrying the graph. */
	public static final String CALLS_METER = "gateway.service.graph.calls";

	/** Tag naming the caller. */
	public static final String CALLER_TAG = "caller";

	/** Tag naming the service the call was routed to. */
	public static final String SERVICE_TAG = "service";

	/** Tag naming the route the call went through. */
	public static final String ROUTE_TAG = "route";

	/** Tag carrying how the call ended. */
	public static final String OUTCOME_TAG = "outcome";

	/** Outcome of a call answered with anything but an error. */
	public static final String SUCCESS = "success";

	/** Outcome of a call answered with a 4xx. */
	public static final String CLIENT_ERROR = "client-error";

	/** Outcome of a call answered with a 5xx. */
	public static final String SERVER_ERROR = "server-error";

	/** Outcome of a call whose response status could not be read. */
	public static final String UNKNOWN_OUTCOME = "unknown";

	/** Ports a target URI carries implicitly, which naming a node must not spell out. */
	private static final Map<String, Integer> DEFAULT_PORTS = Map.of("http", 80, "https", 443, "ws", 80, "wss", 443);

	private static final Logger LOG = LoggerFactory.getLogger(ServiceGraphFilter.class);

	private final ObjectProvider<MeterRegistry> meterRegistry;

	private final CallerResolver callerResolver;

	/**
	 * Creates the filter.
	 * @param meterRegistry the provider over the application meter registry, absent in an
	 * application that publishes no metrics
	 * @param callerResolver the resolver naming the caller
	 */
	public ServiceGraphFilter(ObjectProvider<MeterRegistry> meterRegistry, CallerResolver callerResolver) {
		this.meterRegistry = meterRegistry;
		this.callerResolver = callerResolver;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Runs first so the count is taken around the whole chain, whatever the filters after
	 * it do to the exchange.
	 */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// A call that failed is still a call: an upstream that refused the connection or
		// timed out is exactly the edge worth seeing, so it is counted before the failure
		// is propagated.
		return chain.filter(exchange).then(count(exchange)).onErrorResume((ex) -> count(exchange).then(Mono.error(ex)));
	}

	private Mono<Void> count(ServerWebExchange exchange) {
		MeterRegistry registry = this.meterRegistry.getIfAvailable();
		Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
		if (registry == null || route == null || !StringUtils.hasText(route.getId())) {
			return Mono.empty();
		}
		String routeId = route.getId();
		String service = targetService(route);
		String outcome = outcome(exchange);
		return this.callerResolver.resolve(exchange)
			.doOnNext((caller) -> registry
				.counter(CALLS_METER, CALLER_TAG, caller, SERVICE_TAG, service, ROUTE_TAG, routeId, OUTCOME_TAG,
						outcome)
				.increment())
			.onErrorResume((ex) -> {
				// The graph is an observation: losing an edge must never cost the
				// response the gateway is about to return.
				LOG.debug("Could not count a call for the service graph", ex);
				return Mono.empty();
			})
			.then();
	}

	/**
	 * Names the far end of the edge after what the route targets: the service id for a
	 * load-balanced route ({@code lb://orders} is {@code orders}), the host otherwise,
	 * with the port only when it is not the default one of the scheme.
	 * <p>
	 * The default port is dropped because the gateway adds it: a route declared
	 * {@code http://orders} is normalised to {@code http://orders:80} when it is built,
	 * and a node named {@code orders:80} would be the same service under another name as
	 * soon as one route spells the port and another does not.
	 * <p>
	 * A route that targets no host &mdash; a {@code forward:} to something the gateway
	 * serves itself &mdash; falls back to its own id, which is then the only name that
	 * end of the edge has.
	 * @param route the route that served the call
	 * @return the id of the node the call reached
	 */
	static String targetService(Route route) {
		URI uri = route.getUri();
		if (uri == null || !StringUtils.hasText(uri.getHost())) {
			return route.getId();
		}
		Integer defaultPort = DEFAULT_PORTS.get(uri.getScheme());
		if (uri.getPort() <= 0 || (defaultPort != null && defaultPort == uri.getPort())) {
			return uri.getHost();
		}
		return uri.getHost() + ":" + uri.getPort();
	}

	private static String outcome(ServerWebExchange exchange) {
		HttpStatusCode status = exchange.getResponse().getStatusCode();
		if (status == null) {
			return UNKNOWN_OUTCOME;
		}
		if (status.is5xxServerError()) {
			return SERVER_ERROR;
		}
		if (status.is4xxClientError()) {
			return CLIENT_ERROR;
		}
		return SUCCESS;
	}

}
