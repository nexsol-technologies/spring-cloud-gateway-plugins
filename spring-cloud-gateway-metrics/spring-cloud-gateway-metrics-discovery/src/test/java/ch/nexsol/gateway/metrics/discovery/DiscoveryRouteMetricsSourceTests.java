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

package ch.nexsol.gateway.metrics.discovery;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import ch.nexsol.gateway.metrics.RouteMetric;
import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests the source consolidating what every registered instance counted.
 */
class DiscoveryRouteMetricsSourceTests {

	private final List<MockWebServer> instances = new ArrayList<>();

	@AfterEach
	void stopInstances() throws IOException {
		for (MockWebServer instance : this.instances) {
			instance.shutdown();
		}
	}

	/**
	 * Starts an instance answering the local-metrics path with the given body, and
	 * anything else with 404 &mdash; so a fan-out that called the wrong path would be
	 * caught rather than silently returning nothing.
	 */
	private MockWebServer instance(String body) throws IOException {
		MockWebServer server = new MockWebServer();
		server.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				if (!DiscoveryMetricsProperties.DEFAULT_PATH.equals(request.getPath())) {
					return new MockResponse().setResponseCode(404);
				}
				return new MockResponse().setResponseCode(200)
					.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.setBody(body);
			}
		});
		server.start();
		this.instances.add(server);
		return server;
	}

	private static String metrics(String routeId, long count, double avgMs, long errors) {
		return "[{\"routeId\":\"" + routeId + "\",\"uri\":\"http://" + routeId + "\",\"count\":" + count + ",\"avgMs\":"
				+ avgMs + ",\"maxMs\":" + avgMs + ",\"clientErrorCount\":0,\"clientErrorRate\":0.0,\"errorCount\":"
				+ errors + ",\"errorRate\":0.0}]";
	}

	private DiscoveryRouteMetricsSource sourceOver(MockWebServer... servers) {
		List<ServiceInstance> registered = new ArrayList<>();
		for (int i = 0; i < servers.length; i++) {
			registered.add(new DefaultServiceInstance("gateway-" + i, "gateway", servers[i].getHostName(),
					servers[i].getPort(), false));
		}
		return sourceOver(registered);
	}

	private DiscoveryRouteMetricsSource sourceOver(List<ServiceInstance> registered) {
		ReactiveDiscoveryClient client = new ReactiveDiscoveryClient() {
			@Override
			public String description() {
				return "test";
			}

			@Override
			public Flux<ServiceInstance> getInstances(String serviceId) {
				return Flux.fromIterable(registered);
			}

			@Override
			public Flux<String> getServices() {
				return Flux.just("gateway");
			}
		};
		DiscoveryMetricsProperties properties = new DiscoveryMetricsProperties();
		properties.setTimeout(Duration.ofSeconds(5));
		return new DiscoveryRouteMetricsSource(client, WebClient.builder().build(), properties, "gateway");
	}

	@Test
	void sumsWhatEveryInstanceCounted() throws IOException {
		MockWebServer first = instance(metrics("orders", 100, 10.0, 0));
		MockWebServer second = instance(metrics("orders", 300, 50.0, 8));

		RouteMetricsSnapshot snapshot = sourceOver(first, second).collect().block();

		assertThat(snapshot.coverage()).isEqualTo("2 instances, consolidated");
		assertThat(snapshot.metrics()).singleElement().satisfies((metric) -> {
			assertThat(metric.count()).isEqualTo(400);
			// Weighted, not the 30 ms an average of averages would give.
			assertThat(metric.avgMs()).isCloseTo(40.0, within(0.001));
			assertThat(metric.errorCount()).isEqualTo(8);
		});
	}

	@Test
	void pollsTheLocalPathSoTheFanOutDoesNotCallItselfForever() throws IOException, InterruptedException {
		MockWebServer only = instance(metrics("orders", 10, 1.0, 0));

		sourceOver(only).collect().block();

		// The consolidated endpoint would have every instance poll every other one; the
		// path polled must be the one serving the local registry.
		assertThat(only.takeRequest().getPath()).isEqualTo(DiscoveryMetricsProperties.DEFAULT_PATH);
	}

	@Test
	void keepsTheFiguresOfTheInstancesThatAnswered() throws IOException {
		MockWebServer healthy = instance(metrics("orders", 100, 10.0, 0));
		MockWebServer dead = instance(metrics("orders", 100, 10.0, 0));
		dead.shutdown();

		RouteMetricsSnapshot snapshot = sourceOver(healthy, dead).collect().block();

		assertThat(snapshot.metrics()).singleElement().satisfies((metric) -> assertThat(metric.count()).isEqualTo(100));
		assertThat(snapshot.coverage()).isEqualTo("1 of 2 instances (the others did not answer)");
	}

	@Test
	void saysSoWhenNothingIsRegistered() {
		RouteMetricsSnapshot snapshot = sourceOver(List.of()).collect().block();

		assertThat(snapshot.metrics()).isEmpty();
		assertThat(snapshot.coverage()).isEqualTo("no instance registered as 'gateway'");
	}

	@Test
	void reportsASingleInstanceInTheSingular() throws IOException {
		MockWebServer only = instance(metrics("orders", 10, 1.0, 0));

		assertThat(sourceOver(only).collect().block().coverage()).isEqualTo("1 instance, consolidated");
	}

	@Test
	void reportsNothingRatherThanFailingWhenEveryInstanceIsUnreachable() throws IOException {
		MockWebServer dead = instance(metrics("orders", 10, 1.0, 0));
		dead.shutdown();

		RouteMetricsSnapshot snapshot = sourceOver(dead).collect().block();

		assertThat(snapshot.metrics()).isEmpty();
		assertThat(snapshot.coverage()).isEqualTo("0 of 1 instances (the others did not answer)");
	}

	@Test
	void keepsDistinctRoutesApartWhileMerging() throws IOException {
		MockWebServer first = instance(
				"[" + metrics("orders", 10, 1.0, 0).substring(1, metrics("orders", 10, 1.0, 0).length() - 1) + ","
						+ metrics("billing", 5, 2.0, 0).substring(1, metrics("billing", 5, 2.0, 0).length() - 1) + "]");
		MockWebServer second = instance(metrics("orders", 10, 3.0, 0));

		List<RouteMetric> metrics = sourceOver(first, second).collect().block().metrics();

		assertThat(metrics).extracting(RouteMetric::routeId).containsExactly("orders", "billing");
		assertThat(metrics.get(0).count()).isEqualTo(20);
	}

	@Test
	void resolvesEachInstanceOnItsOwnAddress() throws IOException {
		MockWebServer first = instance(metrics("orders", 7, 1.0, 0));
		MockWebServer second = instance(metrics("orders", 3, 1.0, 0));

		RouteMetricsSnapshot snapshot = sourceOver(first, second).collect().block();

		// 7 + 3: both were polled, not one of them twice.
		assertThat(snapshot.metrics()).singleElement().satisfies((metric) -> assertThat(metric.count()).isEqualTo(10));
	}

	@Test
	void buildsTheInstanceUriFromTheRegistryEntry() throws IOException {
		MockWebServer only = instance(metrics("orders", 10, 1.0, 0));
		ServiceInstance instance = new DefaultServiceInstance("gateway-0", "gateway", only.getHostName(),
				only.getPort(), false);

		assertThat(instance.getUri().resolve(DiscoveryMetricsProperties.DEFAULT_PATH)).isEqualTo(URI
			.create("http://" + only.getHostName() + ":" + only.getPort() + DiscoveryMetricsProperties.DEFAULT_PATH));
	}

}
