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

package ch.nexsol.gateway.routes.configserver.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import ch.nexsol.gateway.routes.configserver.ConfigServerRouteDefinitionLocator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration tests wiring {@link RoutesConfigServerAutoConfiguration} end-to-end
 * and verifying the initial fetch performed by the lifecycle against a stubbed client.
 */
class RoutesConfigServerAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(RoutesConfigServerAutoConfiguration.class))
		.withBean(WebClient.Builder.class, () -> WebClient.builder().exchangeFunction(stubExchange()));

	@Test
	void locatorIsAbsentWhenDisabled() {
		this.runner.run((context) -> assertThat(context).doesNotHaveBean(ConfigServerRouteDefinitionLocator.class));
	}

	@Test
	void locatorLoadsRoutesWhenEnabled() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.routes-configserver.enabled=true",
					"spring.cloud.gateway.server.webflux.routes-configserver.urls="
							+ "http://config:8888/gateway/default/main/orders.yaml")
			.run((context) -> {
				assertThat(context).hasSingleBean(ConfigServerRouteDefinitionLocator.class);
				ConfigServerRouteDefinitionLocator locator = context.getBean(ConfigServerRouteDefinitionLocator.class);
				assertThat(locator.getRouteDefinitions().map(RouteDefinition::getId).collectList().block())
					.containsExactly("orders_route");
			});
	}

	private static ExchangeFunction stubExchange() {
		return (request) -> Mono.just(ClientResponse.create(HttpStatus.OK)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
			.body(read("/routes/orders-routes.yaml"))
			.build());
	}

	private static String read(String resource) {
		try (InputStream input = RoutesConfigServerAutoConfigurationTests.class.getResourceAsStream(resource)) {
			return new String(input.readAllBytes());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
