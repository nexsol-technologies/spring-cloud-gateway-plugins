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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DiscoveryInstanceMetricsSource}.
 */
class DiscoveryInstanceMetricsSourceTests {

	private final List<MockWebServer> instances = new ArrayList<>();

	@AfterEach
	void stopInstances() throws IOException {
		for (MockWebServer instance : this.instances) {
			instance.shutdown();
		}
	}

	/**
	 * Starts an instance answering the local instance path, and anything else with 404
	 * &mdash; so a fan-out calling the route path by mistake would be caught rather than
	 * silently returning nothing.
	 */
	private MockWebServer instance(String body) throws IOException {
		MockWebServer server = new MockWebServer();
		server.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				if (!DiscoveryMetricsProperties.DEFAULT_INSTANCE_PATH.equals(request.getPath())) {
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

	private static String figures(String instanceId, long heapUsed) {
		return "{\"instanceId\":\"" + instanceId + "\",\"uri\":null,\"uptimeSeconds\":90,"
				+ "\"jvm\":{\"heapUsedBytes\":" + heapUsed + ",\"heapMaxBytes\":4000,\"nonHeapUsedBytes\":50,"
				+ "\"gcOverhead\":0.01,\"gcPauseTotalMs\":12.0,\"gcPauseCount\":3,\"threadsLive\":40,"
				+ "\"threadsPeak\":55,\"threadsDaemon\":20},"
				+ "\"system\":{\"processCpuUsage\":0.2,\"systemCpuUsage\":0.4,\"loadAverage1m\":1.5,\"cpuCount\":8,"
				+ "\"openFiles\":100,\"maxFiles\":1024},"
				+ "\"netty\":{\"eventLoopPendingTasks\":0,\"eventLoops\":8},\"pools\":[],"
				+ "\"instrumentation\":{\"connectionPool\":true,\"httpClient\":true}}";
	}

	private DiscoveryInstanceMetricsSource sourceOver(MockWebServer... servers) {
		List<ServiceInstance> registered = new ArrayList<>();
		for (int i = 0; i < servers.length; i++) {
			registered.add(new DefaultServiceInstance("gateway-" + i, "gateway", servers[i].getHostName(),
					servers[i].getPort(), false));
		}
		return sourceOver(registered);
	}

	private DiscoveryInstanceMetricsSource sourceOver(List<ServiceInstance> registered) {
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
		return new DiscoveryInstanceMetricsSource(providerOf(client), WebClient.builder().build(), properties,
				"gateway");
	}

	@SuppressWarnings("unchecked")
	private static ObjectProvider<ReactiveDiscoveryClient> providerOf(ReactiveDiscoveryClient client) {
		ObjectProvider<ReactiveDiscoveryClient> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(client);
		return provider;
	}

	@Test
	void saysSoWhenTheApplicationRegisteredNoDiscoveryClient() {
		DiscoveryInstanceMetricsSource source = new DiscoveryInstanceMetricsSource(providerOf(null),
				WebClient.builder().build(), new DiscoveryMetricsProperties(), "gateway");

		InstanceMetricsSnapshot snapshot = source.collect().block();

		assertThat(snapshot.instances()).isEmpty();
		assertThat(snapshot.coverage()).isEqualTo("service discovery is not enabled");
	}

	@Test
	void keepsOneRowPerInstanceRatherThanMergingThem() throws IOException {
		MockWebServer first = instance(figures("gateway-a", 1000));
		MockWebServer second = instance(figures("gateway-b", 2000));

		InstanceMetricsSnapshot snapshot = sourceOver(first, second).collect().block();

		assertThat(snapshot.coverage()).isEqualTo("2 instances, consolidated");
		// Summing these would produce a gateway with 3000 bytes of heap, which is not a
		// thing that exists.
		assertThat(snapshot.instances()).extracting(InstanceMetric::instanceId)
			.containsExactly("gateway-a", "gateway-b");
		assertThat(snapshot.instances()).extracting((metric) -> metric.jvm().heapUsedBytes())
			.containsExactly(1000L, 2000L);
	}

	@Test
	void stampsTheAddressTheRegistryKnowsEachInstanceBy() throws IOException {
		MockWebServer server = instance(figures("gateway-a", 1000));

		InstanceMetricsSnapshot snapshot = sourceOver(server).collect().block();

		// An instance cannot know where it is reachable from; the registry can.
		assertThat(snapshot.instances()).singleElement()
			.extracting(InstanceMetric::uri)
			.asString()
			.contains(String.valueOf(server.getPort()));
	}

	@Test
	void leavesAnUnreachableInstanceOutRatherThanLosingTheOthers() throws IOException {
		MockWebServer alive = instance(figures("gateway-a", 1000));
		MockWebServer stopped = instance(figures("gateway-b", 2000));
		stopped.shutdown();
		this.instances.remove(stopped);

		InstanceMetricsSnapshot snapshot = sourceOver(alive, stopped).collect().block();

		assertThat(snapshot.instances()).hasSize(1);
		assertThat(snapshot.coverage()).isEqualTo("1 of 2 instances (the others did not answer)");
	}

	@Test
	void saysSoWhenNothingIsRegisteredUnderThatServiceId() {
		InstanceMetricsSnapshot snapshot = sourceOver(List.of()).collect().block();

		assertThat(snapshot.instances()).isEmpty();
		assertThat(snapshot.coverage()).isEqualTo("no instance registered as 'gateway'");
	}

}
