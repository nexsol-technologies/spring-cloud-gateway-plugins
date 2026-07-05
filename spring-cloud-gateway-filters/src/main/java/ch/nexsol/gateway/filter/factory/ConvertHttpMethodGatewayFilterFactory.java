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

package ch.nexsol.gateway.filter.factory;

import java.util.Arrays;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpMethod;
import org.springframework.validation.annotation.Validated;

/**
 * Gateway filter factory that rewrites the HTTP method of the incoming request to a
 * configured replacement method before the request is forwarded downstream.
 */
public class ConvertHttpMethodGatewayFilterFactory
		extends AbstractGatewayFilterFactory<ConvertHttpMethodGatewayFilterFactory.Config> {

	private static final Logger LOG = LoggerFactory.getLogger(ConvertHttpMethodGatewayFilterFactory.class);

	private static final String REPLACEMENT_KEY = "replacement";

	/**
	 * Creates the factory bound to its {@link Config} type.
	 */
	public ConvertHttpMethodGatewayFilterFactory() {
		super(Config.class);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Maps the single shortcut argument to the {@code replacement} configuration field.
	 */
	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(REPLACEMENT_KEY);
	}

	/**
	 * Builds a filter that mutates the exchange to use the configured replacement HTTP
	 * method.
	 * @param config the filter configuration holding the replacement method
	 * @return a gateway filter that swaps the request method
	 */
	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> Mono.just(exchange.getRequest())
			.doOnNext((req) -> LOG.debug("changing method from {} to {}", req.getMethod().toString(),
					config.getReplacement().toString()))
			.map((req) -> exchange.mutate().request(req.mutate().method(config.getReplacement()).build()).build())
			.flatMap(chain::filter);
	}

	/**
	 * Configuration for {@link ConvertHttpMethodGatewayFilterFactory}.
	 */
	@Validated
	public static class Config {

		@NotNull
		private HttpMethod replacement;

		/**
		 * Returns the HTTP method the request will be rewritten to.
		 * @return the replacement HTTP method
		 */
		public HttpMethod getReplacement() {
			return this.replacement;
		}

		/**
		 * Sets the replacement HTTP method from its textual name.
		 * @param method the HTTP method name to resolve
		 */
		public void setReplacement(String method) {
			this.replacement = HttpMethod.valueOf(method);
		}

	}

}
