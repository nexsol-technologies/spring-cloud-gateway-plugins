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

package ch.nexsol.gateway.ui.servicegraph;

import java.util.List;

import ch.nexsol.gateway.servicegraph.GraphEdge;
import ch.nexsol.gateway.servicegraph.ServiceGraphSnapshot;
import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ServiceGraphOverviewContribution}.
 */
class ServiceGraphOverviewContributionTests {

	@Test
	void countsTheServicesTheGatewayReached() {
		ServiceGraphSource source = () -> Mono.just(ServiceGraphSnapshot.of("this instance only (pod-a)",
				List.of(new GraphEdge("frontend", "service-a", "a-route", 10, 1),
						new GraphEdge("service-a", "service-b", "b-route", 4, 0))));

		StepVerifier.create(contribution(source).stats()).assertNext((stat) -> {
			assertThat(stat.label()).isEqualTo("Services called");
			// service-a and service-b are services; frontend only ever called.
			assertThat(stat.value()).isEqualTo("2");
			assertThat(stat.detail()).isEqualTo("2 calls drawn — this instance only (pod-a)");
		}).verifyComplete();
	}

	@Test
	void singularWhenOneCallIsDrawn() {
		ServiceGraphSource source = () -> Mono
			.just(ServiceGraphSnapshot.of("test", List.of(new GraphEdge("web", "orders", "orders-route", 3, 0))));

		StepVerifier.create(contribution(source).stats())
			.assertNext((stat) -> assertThat(stat.detail()).startsWith("1 call drawn"))
			.verifyComplete();
	}

	@Test
	void contributesNothingWithoutASource() {
		StepVerifier.create(contribution(null).stats()).verifyComplete();
	}

	@Test
	void contributesTheEmptyGraphRatherThanNothing() {
		ServiceGraphSource source = () -> Mono.just(ServiceGraphSnapshot.empty("no source"));

		StepVerifier.create(contribution(source).stats())
			.assertNext((stat) -> assertThat(stat.value()).isEqualTo("0"))
			.verifyComplete();
	}

	private static ServiceGraphOverviewContribution contribution(ServiceGraphSource source) {
		@SuppressWarnings("unchecked")
		ObjectProvider<ServiceGraphSource> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(source);
		return new ServiceGraphOverviewContribution(provider);
	}

}
