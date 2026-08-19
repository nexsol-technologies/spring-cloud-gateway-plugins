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

import ch.nexsol.gateway.commons.InstanceIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;

import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.CALLER_TAG;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.CALLS_METER;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.OUTCOME_TAG;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.ROUTE_TAG;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.SERVICE_TAG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LocalServiceGraphSource}.
 */
class LocalServiceGraphSourceTests {

	private SimpleMeterRegistry registry;

	@BeforeEach
	void setUp() {
		this.registry = new SimpleMeterRegistry();
	}

	@Test
	void readsOneEdgePerCounterAndMergesTheOutcomes() {
		count("web", "orders", "orders-route", ServiceGraphFilter.SUCCESS, 7);
		count("web", "orders", "orders-route", ServiceGraphFilter.SERVER_ERROR, 3);

		ServiceGraphSnapshot snapshot = source(this.registry).collect().block();

		assertThat(snapshot.edges()).containsExactly(new GraphEdge("web", "orders", "orders-route", 10, 3));
	}

	@Test
	void countsOnlyTheServerErrorsAsFailures() {
		count("web", "orders", "orders-route", ServiceGraphFilter.CLIENT_ERROR, 4);

		ServiceGraphSnapshot snapshot = source(this.registry).collect().block();

		assertThat(snapshot.edges()).containsExactly(new GraphEdge("web", "orders", "orders-route", 4, 0));
	}

	@Test
	void namesTheInstanceTheGraphWasReadFrom() {
		ServiceGraphSnapshot snapshot = source(this.registry).collect().block();

		assertThat(snapshot.coverage()).isEqualTo("this instance only (gateway-1)");
	}

	@Test
	void reportsNoGraphWhenTheApplicationPublishesNoMetrics() {
		ServiceGraphSnapshot snapshot = source(null).collect().block();

		assertThat(snapshot.edges()).isEmpty();
		assertThat(snapshot.nodes()).isEmpty();
	}

	@Test
	void ignoresACounterThatCarriesNeitherEndpoint() {
		this.registry.counter(CALLS_METER, "something", "else").increment();

		ServiceGraphSnapshot snapshot = source(this.registry).collect().block();

		assertThat(snapshot.edges()).isEmpty();
	}

	private void count(String caller, String service, String route, String outcome, int times) {
		for (int i = 0; i < times; i++) {
			this.registry
				.counter(CALLS_METER, CALLER_TAG, caller, SERVICE_TAG, service, ROUTE_TAG, route, OUTCOME_TAG, outcome)
				.increment();
		}
	}

	private static LocalServiceGraphSource source(MeterRegistry registry) {
		@SuppressWarnings("unchecked")
		ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(registry);
		return new LocalServiceGraphSource(provider, new InstanceIdentity("gateway-1"));
	}

}
