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

public class ConvertHttpMethodGatewayFilterFactory
		extends AbstractGatewayFilterFactory<ConvertHttpMethodGatewayFilterFactory.Config> {

	private static final Logger LOG = LoggerFactory.getLogger(ConvertHttpMethodGatewayFilterFactory.class);

	private static final String REPLACEMENT_KEY = "replacement";

	public ConvertHttpMethodGatewayFilterFactory() {
		super(Config.class);
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(REPLACEMENT_KEY);
	}

	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> {
			return Mono.just(exchange.getRequest())
				.doOnNext((req) -> LOG.debug("changing method from {} to {}", req.getMethod().toString(),
						config.getReplacement().toString()))
				.map((req) -> exchange.mutate().request(req.mutate().method(config.getReplacement()).build()).build())
				.flatMap(chain::filter);
		};
	}

	@Validated
	public static class Config {

		@NotNull
		private HttpMethod replacement;

		public HttpMethod getReplacement() {
			return this.replacement;
		}

		public void setReplacement(String method) {
			this.replacement = HttpMethod.valueOf(method);
			assert this.replacement != null;
		}

	}

}
