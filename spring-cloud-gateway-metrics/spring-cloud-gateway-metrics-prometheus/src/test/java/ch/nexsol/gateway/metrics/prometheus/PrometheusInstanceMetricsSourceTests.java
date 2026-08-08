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
import java.util.List;

import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetric.PoolStats;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
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
 * Tests for {@link PrometheusInstanceMetricsSource}.
 */
class PrometheusInstanceMetricsSourceTests {

	private MockWebServer prometheus;

	private String lastQuery;

	@BeforeEach
	void startPrometheus() throws IOException {
		this.prometheus = new MockWebServer();
		this.prometheus.start();
	}

	@AfterEach
	void stopPrometheus() throws IOException {
		this.prometheus.shutdown();
	}

	private PrometheusInstanceMetricsSource source() {
		return source(new PrometheusMetricsProperties());
	}

	private PrometheusInstanceMetricsSource source(PrometheusMetricsProperties properties) {
		properties.setUrl(this.prometheus.url("/").toString());
		properties.setTimeout(Duration.ofSeconds(5));
		return new PrometheusInstanceMetricsSource(WebClient.builder().baseUrl(properties.getUrl()).build(),
				properties);
	}

	private void respond(String body) {
		this.prometheus.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				PrometheusInstanceMetricsSourceTests.this.lastQuery = URLDecoder.decode(request.getPath(),
						StandardCharsets.UTF_8);
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

	/** Renders one Prometheus sample: a label map and a value. */
	private static String sample(String labels, double value) {
		return "{\"metric\":{" + labels + "},\"value\":[1700000000,\"" + value + "\"]}";
	}

	private static String vector(String... samples) {
		return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + String.join(",", samples)
				+ "]}}";
	}

	private static String named(String metric, String instance, double value) {
		return sample("\"__name__\":\"" + metric + "\",\"instance\":\"" + instance + "\"", value);
	}

	@Test
	void asksForEveryCounterInOneQuery() {
		respond(vector());

		source().collect().block();

		// Twenty round trips to read twenty counters would make this the most expensive
		// source instead of the cheapest one.
		assertThat(this.prometheus.getRequestCount()).isEqualTo(1);
		assertThat(this.lastQuery).contains("__name__=~")
			.contains("jvm_memory_used_bytes")
			.contains("reactor_netty_connection_provider_active_connections");
	}

	@Test
	void dropsAnInstanceThatStoppedReporting() {
		respond(vector());
		PrometheusMetricsProperties properties = new PrometheusMetricsProperties();
		properties.setStaleAfter(Duration.ofMinutes(3));

		source(properties).collect().block();

		// Prometheus keeps the series of instances that no longer run, so the window is
		// what turns the answer into a list of live instances.
		assertThat(this.lastQuery).contains("last_over_time(").contains("[180s]");
	}

	@Test
	void keepsOneRowPerInstance() {
		respond(vector(named("process_uptime_seconds", "10.0.0.1:8080", 900),
				named("jvm_threads_live_threads", "10.0.0.1:8080", 40),
				named("process_uptime_seconds", "10.0.0.2:8080", 120),
				named("jvm_threads_live_threads", "10.0.0.2:8080", 62)));

		InstanceMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.coverage()).isEqualTo("2 instances, from Prometheus");
		assertThat(snapshot.instances()).extracting(InstanceMetric::instanceId)
			.containsExactly("10.0.0.1:8080", "10.0.0.2:8080");
		assertThat(snapshot.instances().get(0).uptimeSeconds()).isEqualTo(900);
		assertThat(snapshot.instances().get(1).jvm().threadsLive()).isEqualTo(62);
	}

	@Test
	void sumsTheMemoryPoolsOfOneInstanceButNotAcrossInstances() {
		respond(vector(
				sample("\"__name__\":\"jvm_memory_used_bytes\",\"instance\":\"a\",\"area\":\"heap\",\"id\":\"eden\"",
						300),
				sample("\"__name__\":\"jvm_memory_used_bytes\",\"instance\":\"a\",\"area\":\"heap\",\"id\":\"old\"",
						700),
				sample("\"__name__\":\"jvm_memory_used_bytes\",\"instance\":\"a\",\"area\":\"nonheap\",\"id\":\"meta\"",
						50),
				sample("\"__name__\":\"jvm_memory_used_bytes\",\"instance\":\"b\",\"area\":\"heap\",\"id\":\"old\"",
						400)));

		InstanceMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.instances().get(0).jvm().heapUsedBytes()).isEqualTo(1000);
		assertThat(snapshot.instances().get(0).jvm().nonHeapUsedBytes()).isEqualTo(50);
		assertThat(snapshot.instances().get(1).jvm().heapUsedBytes()).isEqualTo(400);
	}

	@Test
	void foldsThePoolSeriesPerProviderAndDownstreamAddress() {
		String labels = "\"instance\":\"a\",\"name\":\"proxy\",\"remote_address\":\"service-a:8080\"";
		respond(vector(
				sample("\"__name__\":\"reactor_netty_connection_provider_active_connections\"," + labels
						+ ",\"id\":\"1\"", 20),
				sample("\"__name__\":\"reactor_netty_connection_provider_active_connections\"," + labels
						+ ",\"id\":\"2\"", 27),
				sample("\"__name__\":\"reactor_netty_connection_provider_max_connections\"," + labels, 50),
				sample("\"__name__\":\"reactor_netty_connection_provider_pending_connections_time_seconds_sum\","
						+ labels, 1.7),
				sample("\"__name__\":\"reactor_netty_connection_provider_pending_connections_time_seconds_count\","
						+ labels, 5)));

		List<PoolStats> pools = source().collect().block().instances().get(0).pools();

		assertThat(pools).singleElement().satisfies((pool) -> {
			assertThat(pool.name()).isEqualTo("proxy");
			assertThat(pool.remoteAddress()).isEqualTo("service-a:8080");
			assertThat(pool.active()).isEqualTo(47.0, within(0.001));
			assertThat(pool.max()).isEqualTo(50.0, within(0.001));
			// Prometheus stores the wait as seconds; the record carries milliseconds.
			assertThat(pool.pendingTimeAvgMs()).isEqualTo(340.0, within(0.001));
		});
	}

	@Test
	void readsTheInstanceUnderTheConfiguredLabel() {
		respond(vector(sample("\"__name__\":\"process_uptime_seconds\",\"pod\":\"gateway-7f9c4\"", 60)));
		PrometheusMetricsProperties properties = new PrometheusMetricsProperties();
		properties.setInstanceLabel("pod");

		InstanceMetricsSnapshot snapshot = source(properties).collect().block();

		// The default 'instance' label is the scrape target, which is a host and port
		// rather than the name the deployment knows the instance by.
		assertThat(snapshot.instances()).singleElement()
			.extracting(InstanceMetric::instanceId)
			.isEqualTo("gateway-7f9c4");
	}

	@Test
	void reportsAMissingCounterAsMinusOneRatherThanZero() {
		respond(vector(named("process_uptime_seconds", "a", 60)));

		InstanceMetric instance = source().collect().block().instances().get(0);

		assertThat(instance.jvm().heapUsedBytes()).isEqualTo(-1);
		assertThat(instance.system().openFiles()).isEqualTo(-1);
	}

	@Test
	void saysSoWhenNoInstanceReportedRecently() {
		respond(vector());

		InstanceMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.instances()).isEmpty();
		assertThat(snapshot.coverage()).isEqualTo("no instance reported to Prometheus recently");
	}

	@Test
	void namesWhyTheReadingFailed() {
		respondWithStatus(401);

		InstanceMetricsSnapshot snapshot = source().collect().block();

		assertThat(snapshot.instances()).isEmpty();
		assertThat(snapshot.coverage()).contains("authentication refused (401)");
	}

	@Test
	void survivesASelectorDeclaredWithNoValue() {
		respond(vector());
		PrometheusMetricsProperties properties = new PrometheusMetricsProperties();
		// 'selector:' in YAML binds to null rather than to the empty default, and the
		// expression is built outside the reactive chain: a failure there would escape
		// the error handling and break the page.
		properties.setSelector(null);

		InstanceMetricsSnapshot snapshot = source(properties).collect().block();

		assertThat(snapshot.instances()).isEmpty();
		assertThat(this.lastQuery).contains("__name__=~").doesNotContain("null");
	}

	@Test
	void restrictsTheSeriesToTheConfiguredSelector() {
		respond(vector());
		PrometheusMetricsProperties properties = new PrometheusMetricsProperties();
		properties.setSelector("job=\"gateway\"");

		source(properties).collect().block();

		assertThat(this.lastQuery).contains("job=\"gateway\"");
	}

}
