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

package ch.nexsol.gateway.servicegraph.prometheus;

import java.util.concurrent.atomic.AtomicReference;

import ch.nexsol.gateway.servicegraph.GraphEdge;
import ch.nexsol.gateway.servicegraph.GraphNode;
import ch.nexsol.gateway.servicegraph.GraphNodeKind;
import ch.nexsol.gateway.servicegraph.ServiceGraphSnapshot;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PrometheusServiceGraphSource}.
 */
class PrometheusServiceGraphSourceTests {

	private final AtomicReference<String> lastQuery = new AtomicReference<>();

	@Test
	void readsAnEdgePerSampleAndMergesTheOutcomes() {
		String body = vector(sample("web", "orders", "orders-route", "success", 7),
				sample("web", "orders", "orders-route", "server-error", 3));

		ServiceGraphSnapshot snapshot = source(answering(body)).collect().block();

		assertThat(snapshot.edges()).containsExactly(new GraphEdge("web", "orders", "orders-route", 10, 3));
		assertThat(snapshot.coverage()).isEqualTo("every instance, from Prometheus");
	}

	@Test
	void countsOnlyTheServerErrorsAsFailures() {
		String body = vector(sample("web", "orders", "orders-route", "client-error", 4));

		ServiceGraphSnapshot snapshot = source(answering(body)).collect().block();

		assertThat(snapshot.edges()).containsExactly(new GraphEdge("web", "orders", "orders-route", 4, 0));
	}

	@Test
	void namesAServiceCallingAnotherOneOnBothSidesOfItsEdges() {
		String body = vector(sample("frontend", "service-a", "a-route", "success", 10),
				sample("service-a", "service-b", "b-route", "success", 4));

		ServiceGraphSnapshot snapshot = source(answering(body)).collect().block();

		assertThat(snapshot.nodes()).contains(new GraphNode("service-a", GraphNodeKind.SERVICE, 14),
				new GraphNode("frontend", GraphNodeKind.CALLER, 10));
	}

	@Test
	void sumsTheCounterByEveryLabelTheEdgeIsMadeOf() {
		source(answering(vector())).collect().block();

		assertThat(this.lastQuery.get())
			.isEqualTo("sum by (caller, service, route, outcome) (gateway_service_graph_calls_total{})");
	}

	@Test
	void restrictsTheSeriesToTheConfiguredSelector() {
		PrometheusServiceGraphProperties properties = new PrometheusServiceGraphProperties();
		properties.setSelector("job=\"gateway\"");

		new PrometheusServiceGraphSource(client(answering(vector())), properties).collect().block();

		assertThat(this.lastQuery.get()).contains("gateway_service_graph_calls_total{job=\"gateway\"}");
	}

	@Test
	void queriesTheEmptySelectorWhenThePropertyWasDeclaredWithNoValue() {
		PrometheusServiceGraphProperties properties = new PrometheusServiceGraphProperties();
		properties.setSelector(null);

		new PrometheusServiceGraphSource(client(answering(vector())), properties).collect().block();

		assertThat(this.lastQuery.get()).contains("gateway_service_graph_calls_total{}");
	}

	@Test
	void ignoresASampleMissingAnEndpoint() {
		String body = vector("{\"metric\":{\"caller\":\"web\",\"route\":\"orders-route\"},\"value\":[1,\"7\"]}");

		assertThat(source(answering(body)).collect().block().edges()).isEmpty();
	}

	@Test
	void saysPrometheusRefusedTheCredentials() {
		ExchangeFunction refusing = (request) -> Mono
			.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).body("no").build());

		ServiceGraphSnapshot snapshot = source(refusing).collect().block();

		assertThat(snapshot.coverage()).isEqualTo("every instance, from Prometheus — authentication refused (401)");
		assertThat(snapshot.edges()).isEmpty();
	}

	@Test
	void saysPrometheusCouldNotBeReached() {
		ExchangeFunction unreachable = (request) -> Mono.error(new IllegalStateException("connection refused"));

		assertThat(source(unreachable).collect().block().coverage())
			.isEqualTo("every instance, from Prometheus — unreachable");
	}

	private static String vector(String... samples) {
		return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + String.join(",", samples)
				+ "]}}";
	}

	private static String sample(String caller, String service, String route, String outcome, long value) {
		return "{\"metric\":{\"caller\":\"" + caller + "\",\"service\":\"" + service + "\",\"route\":\"" + route
				+ "\",\"outcome\":\"" + outcome + "\"},\"value\":[1,\"" + value + "\"]}";
	}

	private ExchangeFunction answering(String body) {
		return (request) -> {
			this.lastQuery.set(request.url().getQuery().replace("query=", ""));
			return Mono.just(ClientResponse.create(HttpStatus.OK)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.body(body)
				.build());
		};
	}

	private PrometheusServiceGraphSource source(ExchangeFunction exchangeFunction) {
		return new PrometheusServiceGraphSource(client(exchangeFunction), new PrometheusServiceGraphProperties());
	}

	private static WebClient client(ExchangeFunction exchangeFunction) {
		return WebClient.builder().baseUrl("http://prometheus:9090").exchangeFunction(exchangeFunction).build();
	}

}
