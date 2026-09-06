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

package ch.nexsol.gateway.metrics.prometheus;

import java.io.IOException;

import ch.nexsol.gateway.metrics.autoconfigure.MetricsAutoConfiguration;
import ch.nexsol.gateway.metrics.prometheus.autoconfigure.PrometheusMetricsAutoConfiguration;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the ceiling the Prometheus client reads an answer under. A query answering one
 * series per route and per instance outgrows the 256&nbsp;KB the codecs stop at by
 * default long before the gateway serving those routes is in any trouble, and
 * {@code max-response-size} is what raises that ceiling for this client alone.
 */
class PrometheusResponseSizeTests {

	private MockWebServer prometheus;

	@BeforeEach
	void startPrometheus() throws IOException {
		this.prometheus = new MockWebServer();
		this.prometheus.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				return new MockResponse().setResponseCode(200)
					.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.setBody(answer(400 * 1024));
			}
		});
		this.prometheus.start();
	}

	@AfterEach
	void stopPrometheus() throws IOException {
		this.prometheus.shutdown();
	}

	@Test
	void readsAnAnswerLargerThanTheCodecCeilingWhenAMaximumIsConfigured() {
		runnerWith("spring.cloud.gateway.server.webflux.metrics.prometheus.max-response-size=2MB")
			.run((context) -> StepVerifier.create(query(context.getBean("prometheusMetricsWebClient", WebClient.class)))
				.assertNext((body) -> assertThat(body).hasSizeGreaterThan(400 * 1024))
				.verifyComplete());
	}

	@Test
	void keepsTheCeilingOfTheApplicationWhenNoMaximumIsConfigured() {
		// Unset, the client is left exactly as the application built it: here a plain
		// builder, so the 256 KB the codecs stop at by default.
		runnerWith()
			.run((context) -> StepVerifier.create(query(context.getBean("prometheusMetricsWebClient", WebClient.class)))
				.verifyErrorSatisfies((ex) -> assertThat(NestedExceptionUtils.getMostSpecificCause(ex))
					.isInstanceOf(DataBufferLimitException.class)));
	}

	@Test
	void refusesAnAnswerLargerThanTheConfiguredMaximum() {
		runnerWith("spring.cloud.gateway.server.webflux.metrics.prometheus.max-response-size=64KB")
			.run((context) -> StepVerifier.create(query(context.getBean("prometheusMetricsWebClient", WebClient.class)))
				.verifyErrorSatisfies((ex) -> assertThat(NestedExceptionUtils.getMostSpecificCause(ex))
					.isInstanceOf(DataBufferLimitException.class)));
	}

	private static Mono<String> query(WebClient client) {
		return client.get().uri("/api/v1/query?query=up").retrieve().bodyToMono(String.class);
	}

	private ApplicationContextRunner runnerWith(String... properties) {
		String[] all = new String[properties.length + 2];
		all[0] = "spring.cloud.gateway.server.webflux.metrics.provider=prometheus";
		all[1] = "spring.cloud.gateway.server.webflux.metrics.prometheus.url=" + this.prometheus.url("/");
		System.arraycopy(properties, 0, all, 2, properties.length);
		return new ApplicationContextRunner().withBean("webClientBuilder", WebClient.Builder.class, WebClient::builder)
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
					PrometheusMetricsAutoConfiguration.class, MetricsAutoConfiguration.class))
			.withPropertyValues(all);
	}

	/**
	 * A Prometheus answer of at least the given size, padded with a label value long
	 * enough to take it past the ceiling under test.
	 */
	private static String answer(int size) {
		return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"padding\":\""
				+ "x".repeat(size) + "\"},\"value\":[0,\"1\"]}]}}";
	}

}
