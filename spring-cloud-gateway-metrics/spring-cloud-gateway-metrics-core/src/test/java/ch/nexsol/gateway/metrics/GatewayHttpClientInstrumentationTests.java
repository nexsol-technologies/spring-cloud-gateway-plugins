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

package ch.nexsol.gateway.metrics;

import java.time.Duration;
import java.util.List;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GatewayHttpClientInstrumentation}.
 * <p>
 * These go through a real exchange rather than inspecting the client configuration,
 * because what has to be proven is that the counters come into existence at all: Reactor
 * Netty registers them from inside the channel pipeline, so a client that merely looks
 * instrumented proves nothing.
 */
class GatewayHttpClientInstrumentationTests {

	private static final String CLIENT_METER_PREFIX = "reactor.netty.http.client";

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private DisposableServer server;

	@BeforeEach
	void startServer() {
		// Reactor Netty publishes into the Micrometer global registry, not into an
		// injected bean, which is exactly how the local source ends up seeing the
		// counters through the registry Spring Boot adds to that same composite.
		Metrics.addRegistry(this.registry);
		this.server = HttpServer.create().port(0).handle((in, out) -> out.sendString(Mono.just("ok"))).bindNow();
	}

	@AfterEach
	void stopServer() {
		this.server.disposeNow();
		Metrics.removeRegistry(this.registry);
		this.registry.close();
	}

	private void exchange(HttpClient client, String uri) {
		client.get().uri(uri).responseContent().aggregate().asString().block(Duration.ofSeconds(5));
	}

	private List<String> uriTagsOfClientMeters() {
		return this.registry.getMeters()
			.stream()
			.map(Meter::getId)
			.filter((id) -> id.getName().startsWith(CLIENT_METER_PREFIX))
			.map((id) -> id.getTag("uri"))
			.filter((uri) -> uri != null)
			.distinct()
			.toList();
	}

	@Test
	void foldsEveryRequestPathIntoTheSameUriTag() {
		HttpClient client = new GatewayHttpClientInstrumentation()
			.customize(HttpClient.create().port(this.server.port()));

		exchange(client, "/orders/42/lines");
		exchange(client, "/customers/7");

		assertThat(this.registry.find(CLIENT_METER_PREFIX + ".data.received").meters()).isNotEmpty();
		// Two distinct paths, no path among the tag values: the cardinality guard is the
		// whole point of the mandatory URI mapper. The connection-level counters carry a
		// fixed protocol name of their own, which costs no cardinality.
		assertThat(uriTagsOfClientMeters()).contains(GatewayHttpClientInstrumentation.AGGREGATE_URI)
			.noneMatch((uri) -> uri.startsWith("/"));
	}

	@Test
	void bringsTheEventLoopCounterIntoExistence() {
		exchange(new GatewayHttpClientInstrumentation().customize(HttpClient.create().port(this.server.port())),
				"/orders/42/lines");

		// The gateway never calls metrics(...) itself, so this counter only exists
		// because the customizer asked for it. The reverse cannot be asserted here: the
		// event loops are shared process-wide, so once anything is instrumented the
		// counters stay registered for the rest of the JVM.
		assertThat(this.registry.find(LocalInstanceMetricsSource.EVENT_LOOP_PENDING_TASKS).meters()).isNotEmpty();
	}

}
