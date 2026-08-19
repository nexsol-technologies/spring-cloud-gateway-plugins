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

package ch.nexsol.gateway.servicegraph.tempo;

import java.util.ArrayList;
import java.util.List;

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
 * Tests for {@link TempoServiceGraphSource}.
 */
class TempoServiceGraphSourceTests {

	private final List<String> queries = new ArrayList<>();

	@Test
	void readsAnEdgePerPairOfServices() {
		ExchangeFunction tempo = answering(vector(pair("frontend", "service-a", 10)),
				vector(pair("frontend", "service-a", 2)));

		ServiceGraphSnapshot snapshot = source(tempo).collect().block();

		assertThat(snapshot.edges()).containsExactly(new GraphEdge("frontend", "service-a", null, 10, 2));
		assertThat(snapshot.coverage()).isEqualTo("every service, from Tempo");
	}

	@Test
	void reportsNoFailureForAPairMissingFromTheFailedSeries() {
		ExchangeFunction tempo = answering(vector(pair("frontend", "service-a", 10), pair("service-a", "service-b", 4)),
				vector(pair("frontend", "service-a", 2)));

		ServiceGraphSnapshot snapshot = source(tempo).collect().block();

		assertThat(snapshot.edges()).contains(new GraphEdge("service-a", "service-b", null, 4, 0));
	}

	@Test
	void namesAServiceOnBothSidesOfItsEdgesOnce() {
		ExchangeFunction tempo = answering(vector(pair("frontend", "service-a", 10), pair("service-a", "service-b", 4)),
				vector());

		ServiceGraphSnapshot snapshot = source(tempo).collect().block();

		assertThat(snapshot.nodes()).contains(new GraphNode("service-a", GraphNodeKind.SERVICE, 14),
				new GraphNode("frontend", GraphNodeKind.CALLER, 10));
	}

	@Test
	void readsTheTwoSeriesOfTheMetricsGenerator() {
		source(answering(vector(), vector())).collect().block();

		assertThat(this.queries).containsExactlyInAnyOrder(
				"sum by (client, server) (traces_service_graph_request_total{})",
				"sum by (client, server) (traces_service_graph_request_failed_total{})");
	}

	@Test
	void restrictsTheSeriesToTheConfiguredSelector() {
		TempoServiceGraphProperties properties = new TempoServiceGraphProperties();
		properties.setSelector("namespace=\"prod\"");

		new TempoServiceGraphSource(client(answering(vector(), vector())), properties).collect().block();

		assertThat(this.queries).allMatch((query) -> query.contains("{namespace=\"prod\"}"));
	}

	@Test
	void readsTheConfiguredLabels() {
		TempoServiceGraphProperties properties = new TempoServiceGraphProperties();
		properties.setClientLabel("source");
		properties.setServerLabel("target");
		String body = "{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{\"source\":\"a\",\"target\":\"b\"},"
				+ "\"value\":[1,\"5\"]}]}}";

		ServiceGraphSnapshot snapshot = new TempoServiceGraphSource(client(answering(body, vector())), properties)
			.collect()
			.block();

		assertThat(snapshot.edges()).containsExactly(new GraphEdge("a", "b", null, 5, 0));
	}

	@Test
	void ignoresASampleMissingAnEndpoint() {
		String body = "{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{\"client\":\"a\"},"
				+ "\"value\":[1,\"5\"]}]}}";

		assertThat(source(answering(body, vector())).collect().block().edges()).isEmpty();
	}

	@Test
	void saysTheServerRefusedTheCredentials() {
		ExchangeFunction refusing = (request) -> Mono
			.just(ClientResponse.create(HttpStatus.FORBIDDEN).body("no").build());

		assertThat(source(refusing).collect().block().coverage())
			.isEqualTo("every service, from Tempo — authentication refused (403)");
	}

	@Test
	void saysTheServerCouldNotBeReached() {
		ExchangeFunction unreachable = (request) -> Mono.error(new IllegalStateException("connection refused"));

		assertThat(source(unreachable).collect().block().coverage())
			.isEqualTo("every service, from Tempo — unreachable");
	}

	private static String vector(String... samples) {
		return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + String.join(",", samples)
				+ "]}}";
	}

	private static String pair(String client, String server, long value) {
		return "{\"metric\":{\"client\":\"" + client + "\",\"server\":\"" + server + "\"},\"value\":[1,\"" + value
				+ "\"]}";
	}

	/**
	 * Answers the request series with the first body and the failed series with the
	 * second, whichever order the source asks for them in.
	 */
	private ExchangeFunction answering(String requests, String failures) {
		return (request) -> {
			String query = request.url().getQuery().replace("query=", "");
			this.queries.add(query);
			return Mono.just(ClientResponse.create(HttpStatus.OK)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.body(query.contains("failed") ? failures : requests)
				.build());
		};
	}

	private TempoServiceGraphSource source(ExchangeFunction exchangeFunction) {
		return new TempoServiceGraphSource(client(exchangeFunction), new TempoServiceGraphProperties());
	}

	private static WebClient client(ExchangeFunction exchangeFunction) {
		return WebClient.builder().baseUrl("http://mimir:9009/prometheus").exchangeFunction(exchangeFunction).build();
	}

}
