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

package ch.nexsol.gateway.ui.insights;

import java.util.Map;

import ch.nexsol.gateway.ui.insights.ActuatorInstances.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

/**
 * Reads one Actuator endpoint of one instance.
 * <p>
 * Over HTTP rather than through the endpoint beans, so the instance answering the console
 * and any other instance are read by the same code path. What it returns is therefore
 * what Actuator serves and no more: a value Actuator masks arrives masked here, and an
 * endpoint it does not expose comes back as a failure the view reports rather than as
 * data.
 * <p>
 * The address is never taken from the request. It comes from {@link ActuatorInstances},
 * which only ever hands back an instance it knows about; the instance answering the
 * console is read on the address that request arrived on. That is what keeps this from
 * being a hole through which the gateway can be asked to fetch an arbitrary URL.
 */
public class ActuatorClient {

	private static final Logger LOG = LoggerFactory.getLogger(ActuatorClient.class);

	private final WebClient webClient;

	private final InsightsProperties properties;

	private final LocalActuator local;

	/**
	 * Creates the client.
	 * @param webClientBuilder the builder the client is created from
	 * @param properties the introspection configuration
	 * @param local where this instance serves its own Actuator endpoints
	 */
	public ActuatorClient(WebClient.Builder webClientBuilder, InsightsProperties properties, LocalActuator local) {
		this.local = local;
		// The condition report and the bean report of a gateway running every plugin both
		// run past a megabyte, and the client refuses anything over 256 KB by default.
		this.webClient = webClientBuilder
			.codecs((codecs) -> codecs.defaultCodecs().maxInMemorySize(properties.maxPayloadBytes()))
			.build();
		this.properties = properties;
	}

	/**
	 * Reads one endpoint of one instance.
	 * @param exchange the request being served, which carries the address this instance
	 * answers on
	 * @param instance the instance to read
	 * @param endpoint the endpoint to read, e.g. {@code loggers}
	 * @return the payload, or a failure carrying why it could not be read
	 */
	public Mono<Map<String, Object>> read(ServerWebExchange exchange, Instance instance, String endpoint) {
		String url = url(exchange, instance, endpoint);
		return this.webClient.get()
			.uri(url)
			.accept(MediaType.APPLICATION_JSON)
			.retrieve()
			.bodyToMono(new ParameterizedMap())
			.timeout(this.properties.getTimeout())
			.onErrorResume((ex) -> {
				LOG.debug("Could not read the {} endpoint of {}", endpoint, instance.id(), ex);
				return Mono.error(new EndpointUnavailable(endpoint, reason(ex)));
			});
	}

	/**
	 * Writes to one endpoint of one instance, for the single view that writes.
	 * @param exchange the request being served
	 * @param instance the instance to write to
	 * @param endpoint the endpoint path, e.g. {@code loggers/ch.nexsol}
	 * @param body the payload to send
	 * @return a mono completing once the write is acknowledged
	 */
	public Mono<Void> write(ServerWebExchange exchange, Instance instance, String endpoint, Map<String, Object> body) {
		String url = url(exchange, instance, endpoint);
		return this.webClient.post()
			.uri(url)
			.contentType(MediaType.APPLICATION_JSON)
			.body(BodyInserters.fromValue(body))
			.retrieve()
			.toBodilessEntity()
			.timeout(this.properties.getTimeout())
			.onErrorResume((ex) -> {
				LOG.debug("Could not write to the {} endpoint of {}", endpoint, instance.id(), ex);
				return Mono.error(new EndpointUnavailable(endpoint, reason(ex)));
			})
			.then();
	}

	/**
	 * The address of one endpoint. The instance answering the console is read on the
	 * address the request arrived on, which is the only address it is known to be
	 * reachable at &mdash; a port read from the configuration is the port it was asked to
	 * bind, not necessarily the one a reverse proxy is reaching it on.
	 */
	/*
	 * The instance answering the console is read where it actually serves Actuator, which
	 * is not always where it serves the console: `management.server.port` moves the
	 * endpoints to a port of their own, and the base paths move them again. Taking the
	 * port of the incoming request assumes a deployment that never separated the two, and
	 * answers 404 on every one that did.
	 *
	 * Another instance is read on the address it published, with the base path configured
	 * here: nothing tells this console how that instance configured its own management
	 * server.
	 */
	private String url(ServerWebExchange exchange, Instance instance, String endpoint) {
		if (instance.self()) {
			return this.local.url(exchange, endpoint, this.properties.basePathOr(this.local.basePath()));
		}
		return trimmed(instance.uri()) + this.properties.basePathOr(this.local.basePath()) + "/" + endpoint;
	}

	private static String trimmed(String uri) {
		return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
	}

	private static String reason(Throwable ex) {
		String message = ex.getMessage();
		return (message != null) ? message : ex.getClass().getSimpleName();
	}

	/**
	 * Raised when an endpoint could not be read: not exposed, not reachable, or too slow.
	 * The views report it as a state of the page rather than as a broken page.
	 */
	public static class EndpointUnavailable extends RuntimeException {

		private final String endpoint;

		EndpointUnavailable(String endpoint, String reason) {
			super(reason);
			this.endpoint = endpoint;
		}

		/**
		 * @return the endpoint that could not be read
		 */
		public String endpoint() {
			return this.endpoint;
		}

	}

	/**
	 * The shape every Actuator endpoint this console reads answers with. Declared once
	 * rather than inline, since a parameterized type reference cannot be written as a
	 * class literal.
	 */
	private static final class ParameterizedMap
			extends org.springframework.core.ParameterizedTypeReference<Map<String, Object>> {

	}

}
