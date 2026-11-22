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

package ch.nexsol.gateway.routes.configserver;

import java.time.Duration;
import java.util.Map;

import ch.nexsol.gateway.routes.files.RouteDefinitionFileParser;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that a Config Server which accepts the request and never answers does not hold
 * the read for ever. The poll reads one tick at a time: a read that never ends stops the
 * polling for good, and at startup it keeps the gateway from ever becoming ready.
 */
class ConfigServerRouteDefinitionLoaderTimeoutTests {

	private static final String PREFIX = "routes";

	private static final String URL = "http://config:8888/gateway/default/main/orders.yaml";

	@Test
	void givesUpOnAConfigServerThatNeverAnswers() {
		ConfigServerRouteDefinitionLoader loader = loader((request) -> Mono.never(), "200ms");

		StepVerifier.create(loader.load())
			.expectErrorSatisfies((error) -> assertThat(error).isInstanceOf(RouteConfigServerException.class)
				.hasMessageContaining(URL))
			.verify(Duration.ofSeconds(5));
	}

	@Test
	void stillReadsAFileServedWithinTheTimeout() {
		ExchangeFunction answering = (request) -> Mono
			.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
				.body("routes:\n  - id: orders_route\n    uri: https://orders.example.org\n")
				.build())
			.delayElement(Duration.ofMillis(50));
		ConfigServerRouteDefinitionLoader loader = loader(answering, "5s");

		StepVerifier.create(loader.load().map(RouteDefinition::getId))
			.expectNext("orders_route")
			.expectComplete()
			.verify(Duration.ofSeconds(5));
	}

	@Test
	void givesAFileThirtySecondsByDefault() {
		assertThat(properties(null)).extracting("timeout").isEqualTo(Duration.ofSeconds(30));
	}

	private static ConfigServerRouteDefinitionLoader loader(ExchangeFunction exchange, String timeout) {
		return new ConfigServerRouteDefinitionLoader(WebClient.builder().exchangeFunction(exchange).build(),
				new RouteDefinitionFileParser(), properties(timeout));
	}

	private static RoutesConfigServerProperties properties(String timeout) {
		// Bound the way the application binds them, so the setting is given by its name.
		Map<String, String> source = (timeout != null) ? Map.of(PREFIX + ".urls[0]", URL, PREFIX + ".timeout", timeout)
				: Map.of(PREFIX + ".urls[0]", URL);
		return new Binder(new MapConfigurationPropertySource(source)).bind(PREFIX, RoutesConfigServerProperties.class)
			.get();
	}

}
