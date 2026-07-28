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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import ch.nexsol.gateway.metrics.MetricsProperties;
import ch.nexsol.gateway.metrics.RouteMetric;
import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests the source reading the consolidated figures from Prometheus.
 */
class PrometheusRouteMetricsSourceTests {

	private MockWebServer prometheus;

	@BeforeEach
	void startPrometheus() throws IOException {
		this.prometheus = new MockWebServer();
		this.prometheus.start();
	}

	@AfterEach
	void stopPrometheus() throws IOException {
		this.prometheus.shutdown();
	}

	private PrometheusRouteMetricsSource source() {
		return source(new MetricsProperties());
	}

	private PrometheusRouteMetricsSource source(MetricsProperties metricsProperties) {
		PrometheusMetricsProperties properties = new PrometheusMetricsProperties();
		properties.setUrl(this.prometheus.url("/").toString());
		properties.setTimeout(Duration.ofSeconds(5));
		return new PrometheusRouteMetricsSource(WebClient.builder().baseUrl(properties.getUrl()).build(), properties,
				metricsProperties);
	}

	/**
	 * Answers each query on what it asks for rather than on the order it arrives in: the
	 * three queries are issued concurrently, so a FIFO queue of canned responses would
	 * pair them up at random.
	 */
	private void respond(String counts, String totals, String maxima) {
		this.prometheus.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				String query = URLDecoder.decode(request.getPath(), StandardCharsets.UTF_8);
				String body = query.contains("_count") ? counts : (query.contains("_sum") ? totals : maxima);
				return new MockResponse().setResponseCode(200)
					.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.setBody(body);
			}
		});
	}

	private void respondWithStatus(int code) {
		this.prometheus.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				return new MockResponse().setResponseCode(code);
			}
		});
	}

	private static String vector(String... samples) {
		return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + String.join(",", samples)
				+ "]}}";
	}

	private static String sample(String routeId, String status, String value) {
		return "{\"metric\":{\"routeId\":\"" + routeId + "\",\"routeUri\":\"http://" + routeId
				+ "\",\"httpStatusCode\":\"" + status + "\"},\"value\":[1690000000," + '"' + value + "\"]}";
	}

	private static String routeSample(String routeId, String value) {
		return "{\"metric\":{\"routeId\":\"" + routeId + "\"},\"value\":[1690000000,\"" + value + "\"]}";
	}

	@Test
	void reportsTheFiguresOfEveryInstance() {
		// 400 calls totalling 8 s: Prometheus keeps seconds, the view reads milliseconds.
		respond(vector(sample("orders", "200", "400")), vector(sample("orders", "200", "8")),
				vector(routeSample("orders", "1.5")));

		RouteMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.coverage()).isEqualTo("every instance, from Prometheus");
		assertThat(snapshot.metrics()).singleElement().satisfies((metric) -> {
			assertThat(metric.routeId()).isEqualTo("orders");
			assertThat(metric.uri()).isEqualTo("http://orders");
			assertThat(metric.count()).isEqualTo(400);
			assertThat(metric.avgMs()).isCloseTo(20.0, within(0.001));
			assertThat(metric.maxMs()).isCloseTo(1500.0, within(0.001));
		});
	}

	@Test
	void foldsTheStatusSeriesOfARouteIntoOneFigure() {
		respond(vector(sample("orders", "200", "90"), sample("orders", "404", "6"), sample("orders", "500", "4")),
				vector(sample("orders", "200", "0.9"), sample("orders", "404", "0.06"),
						sample("orders", "500", "0.04")),
				vector(routeSample("orders", "0.2")));

		List<RouteMetric> metrics = source().collect().block().metrics();

		assertThat(metrics).singleElement().satisfies((metric) -> {
			assertThat(metric.count()).isEqualTo(100);
			assertThat(metric.clientErrorCount()).isEqualTo(6);
			assertThat(metric.errorCount()).isEqualTo(4);
			assertThat(metric.errorRate()).isCloseTo(0.04, within(0.0001));
		});
	}

	@Test
	void queriesTheConfiguredSelectorSoASharedPrometheusIsNotMixedUp() throws InterruptedException {
		respond(vector(), vector(), vector());
		PrometheusMetricsProperties properties = new PrometheusMetricsProperties();
		properties.setUrl(this.prometheus.url("/").toString());
		properties.setSelector("job=\"gateway\"");
		new PrometheusRouteMetricsSource(WebClient.builder().baseUrl(properties.getUrl()).build(), properties,
				new MetricsProperties())
			.collect()
			.block();

		Deque<String> queries = new ArrayDeque<>();
		for (int i = 0; i < 3; i++) {
			RecordedRequest request = this.prometheus.takeRequest();
			queries.add(URLDecoder.decode(request.getPath(), StandardCharsets.UTF_8));
		}

		assertThat(queries).allSatisfy((query) -> assertThat(query).contains("job=\"gateway\""));
	}

	@Test
	void leavesOutTheExcludedRoutes() {
		respond(vector(sample("orders", "200", "10"), sample("openapi-docs-petstore", "200", "99")),
				vector(sample("orders", "200", "1"), sample("openapi-docs-petstore", "200", "9")), vector());

		List<RouteMetric> metrics = source().collect().block().metrics();

		assertThat(metrics).extracting(RouteMetric::routeId).containsExactly("orders");
	}

	@Test
	void reportsNothingRatherThanFailingWhenPrometheusIsUnreachable() throws IOException {
		this.prometheus.shutdown();

		RouteMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.metrics()).isEmpty();
		assertThat(snapshot.coverage()).contains("unreachable");
	}

	@Test
	void reportsNothingWhenPrometheusAnswersAnError() {
		respondWithStatus(500);

		RouteMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.metrics()).isEmpty();
		assertThat(snapshot.coverage()).contains("refused with 500");
	}

	@Test
	void tellsARefusedCredentialApartFromAnUnreachableServer() {
		// An expired token otherwise looks exactly like a network outage, on a page
		// nobody
		// would think to correlate with a secret rotation.
		respondWithStatus(401);

		assertThat(source().collect().block().coverage()).contains("authentication refused (401)");
	}

	@Test
	void reportsAForbiddenAnswerAsAnAuthenticationProblemToo() {
		respondWithStatus(403);

		assertThat(source().collect().block().coverage()).contains("authentication refused (403)");
	}

	@Test
	void toleratesASeriesWithoutData() {
		// Prometheus renders an absent value as NaN rather than omitting the sample.
		respond(vector(sample("orders", "200", "NaN")), vector(sample("orders", "200", "NaN")), vector());

		List<RouteMetric> metrics = source().collect().block().metrics();

		assertThat(metrics).singleElement().satisfies((metric) -> {
			assertThat(metric.count()).isZero();
			assertThat(metric.avgMs()).isZero();
		});
	}

	@Test
	void reportsNoRouteWhenPrometheusHasNothing() {
		respond(vector(), vector(), vector());

		assertThat(source().collect().block().metrics()).isEmpty();
	}

}
