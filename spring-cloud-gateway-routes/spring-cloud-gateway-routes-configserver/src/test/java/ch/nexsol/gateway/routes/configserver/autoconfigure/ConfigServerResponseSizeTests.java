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

import ch.nexsol.gateway.routes.configserver.ConfigServerRouteDefinitionLoader;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the ceiling the Config Server client reads a route file under. A route file is
 * parsed whole, so it is buffered whole, and the codecs stop at 256&nbsp;KB unless
 * {@code max-response-size} raises that ceiling for this client alone.
 */
class ConfigServerResponseSizeTests {

	private MockWebServer configServer;

	@BeforeEach
	void startConfigServer() throws IOException {
		this.configServer = new MockWebServer();
		this.configServer.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				return new MockResponse().setResponseCode(200)
					.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
					.setBody(routeFile(400 * 1024));
			}
		});
		this.configServer.start();
	}

	@AfterEach
	void stopConfigServer() throws IOException {
		this.configServer.shutdown();
	}

	@Test
	void readsARouteFileLargerThanTheCodecCeilingWhenAMaximumIsConfigured() {
		runnerWith("spring.cloud.gateway.server.webflux.routes-configserver.max-response-size=2MB").run((context) -> {
			ConfigServerRouteDefinitionLoader loader = context.getBean(ConfigServerRouteDefinitionLoader.class);
			StepVerifier.create(loader.load().map(RouteDefinition::getId)).expectNext("orders_route").verifyComplete();
		});
	}

	@Test
	void keepsTheCeilingOfTheApplicationWhenNoMaximumIsConfigured() {
		// Unset, the client is left exactly as the application built it: here a plain
		// builder, so the 256 KB the codecs stop at by default.
		runnerWith().run((context) -> {
			ConfigServerRouteDefinitionLoader loader = context.getBean(ConfigServerRouteDefinitionLoader.class);
			StepVerifier.create(loader.load())
				.verifyErrorSatisfies((ex) -> assertThat(NestedExceptionUtils.getMostSpecificCause(ex))
					.isInstanceOf(DataBufferLimitException.class));
		});
	}

	@Test
	void refusesARouteFileLargerThanTheConfiguredMaximum() {
		runnerWith("spring.cloud.gateway.server.webflux.routes-configserver.max-response-size=64KB").run((context) -> {
			ConfigServerRouteDefinitionLoader loader = context.getBean(ConfigServerRouteDefinitionLoader.class);
			StepVerifier.create(loader.load())
				.verifyErrorSatisfies((ex) -> assertThat(NestedExceptionUtils.getMostSpecificCause(ex))
					.isInstanceOf(DataBufferLimitException.class));
		});
	}

	private ApplicationContextRunner runnerWith(String... properties) {
		String[] all = new String[properties.length + 2];
		all[0] = "spring.cloud.gateway.server.webflux.routes-configserver.enabled=true";
		all[1] = "spring.cloud.gateway.server.webflux.routes-configserver.urls="
				+ this.configServer.url("/orders-routes.yaml");
		System.arraycopy(properties, 0, all, 2, properties.length);
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(RoutesConfigServerAutoConfiguration.class))
			.withBean(WebClient.Builder.class, WebClient::builder)
			.withPropertyValues(all);
	}

	/**
	 * A valid route file of at least the given size, padded with a metadata value long
	 * enough to take it past the ceiling under test.
	 */
	private static String routeFile(int size) {
		return "routes:\n" + "  - id: orders_route\n" + "    uri: https://orders.example.org\n" + "    predicates:\n"
				+ "      - Path=/orders/**\n" + "    metadata:\n" + "      padding: " + "x".repeat(size) + "\n";
	}

}
