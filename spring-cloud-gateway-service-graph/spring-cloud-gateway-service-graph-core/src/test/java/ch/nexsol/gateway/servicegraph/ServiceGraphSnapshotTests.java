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

package ch.nexsol.gateway.servicegraph;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link ServiceGraphSnapshot}.
 */
class ServiceGraphSnapshotTests {

	@Test
	void sumsTheEdgesJoiningTheSameEndpointsThroughTheSameRoute() {
		ServiceGraphSnapshot snapshot = ServiceGraphSnapshot.of("test",
				List.of(new GraphEdge("web", "orders", "orders-route", 3, 0),
						new GraphEdge("web", "orders", "orders-route", 2, 2),
						new GraphEdge("web", "billing", "billing-route", 4, 1)));

		assertThat(snapshot.edges()).containsExactly(new GraphEdge("web", "orders", "orders-route", 5, 2),
				new GraphEdge("web", "billing", "billing-route", 4, 1));
	}

	@Test
	void keepsTheSameEndpointsApartWhenTheCallsTookDifferentRoutes() {
		ServiceGraphSnapshot snapshot = ServiceGraphSnapshot.of("test",
				List.of(new GraphEdge("web", "orders", "orders-read", 3, 0),
						new GraphEdge("web", "orders", "orders-write", 2, 0)));

		assertThat(snapshot.edges()).containsExactly(new GraphEdge("web", "orders", "orders-read", 3, 0),
				new GraphEdge("web", "orders", "orders-write", 2, 0));
	}

	@Test
	void keepsTheEdgesOfDistinctCallersApart() {
		ServiceGraphSnapshot snapshot = ServiceGraphSnapshot.of("test",
				List.of(new GraphEdge("web", "orders", "orders-route", 3, 0),
						new GraphEdge("batch", "orders", "orders-route", 2, 0)));

		assertThat(snapshot.edges()).containsExactly(new GraphEdge("web", "orders", "orders-route", 3, 0),
				new GraphEdge("batch", "orders", "orders-route", 2, 0));
	}

	@Test
	void namesAnEndpointTheGatewayRoutedToAService() {
		ServiceGraphSnapshot snapshot = ServiceGraphSnapshot.of("test",
				List.of(new GraphEdge("web", "orders", "orders-route", 3, 0)));

		assertThat(snapshot.nodes()).containsExactlyInAnyOrder(new GraphNode("orders", GraphNodeKind.SERVICE, 3),
				new GraphNode("web", GraphNodeKind.CALLER, 3));
	}

	@Test
	void reportsAServiceCallingAnotherOneAsASingleServiceNode() {
		ServiceGraphSnapshot snapshot = ServiceGraphSnapshot.of("test",
				List.of(new GraphEdge("frontend", "service-a", "a-route", 10, 0),
						new GraphEdge("service-a", "service-b", "b-route", 4, 0)));

		assertThat(snapshot.nodes()).containsExactly(new GraphNode("service-a", GraphNodeKind.SERVICE, 14),
				new GraphNode("frontend", GraphNodeKind.CALLER, 10),
				new GraphNode("service-b", GraphNodeKind.SERVICE, 4));
	}

	@Test
	void ordersTheEdgesByCallCountDescending() {
		ServiceGraphSnapshot snapshot = ServiceGraphSnapshot.of("test", List
			.of(new GraphEdge("web", "quiet", "quiet-route", 1, 0), new GraphEdge("web", "busy", "busy-route", 9, 0)));

		assertThat(snapshot.edges()).extracting(GraphEdge::to).containsExactly("busy", "quiet");
	}

	@Test
	void reportsNothingWhenNoCallWasCounted() {
		ServiceGraphSnapshot snapshot = ServiceGraphSnapshot.of("test", List.of());

		assertThat(snapshot.coverage()).isEqualTo("test");
		assertThat(snapshot.nodes()).isEmpty();
		assertThat(snapshot.edges()).isEmpty();
	}

	@Test
	void emptyCarriesTheCoverageItWouldHaveHad() {
		assertThat(ServiceGraphSnapshot.empty("Redis unavailable").coverage()).isEqualTo("Redis unavailable");
	}

	@Test
	void cannotBeModifiedThroughItsLists() {
		ServiceGraphSnapshot snapshot = ServiceGraphSnapshot.of("test",
				List.of(new GraphEdge("web", "orders", "orders-route", 1, 0)));

		assertThatExceptionOfType(UnsupportedOperationException.class)
			.isThrownBy(() -> snapshot.edges().add(new GraphEdge("x", "y", "r", 1, 0)));
		assertThatExceptionOfType(UnsupportedOperationException.class)
			.isThrownBy(() -> snapshot.nodes().add(new GraphNode("x", GraphNodeKind.CALLER, 1)));
	}

}
