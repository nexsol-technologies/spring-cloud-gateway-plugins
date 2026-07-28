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

package ch.nexsol.gateway.sample.metrics;

import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the source the sample runs with no provider selected: the figures of this
 * instance, reported with the coverage they were computed over.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class MetricsGatewayApplicationTests {

	@Autowired
	RouteMetricsSource routeMetricsSource;

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldReportTheCoverageAlongWithTheFigures() {
		StepVerifier.create(this.routeMetricsSource.collect())
			.assertNext((snapshot) -> assertThat(snapshot.coverage()).contains("gateway-metrics-1"))
			.verifyComplete();
	}

	@Test
	void shouldServeTheTrafficView() {
		this.webTestClient.get().uri("/ui/metrics").exchange().expectStatus().isOk();
	}

	@Test
	void shouldReportNoFigureRatherThanFailingBeforeAnyTrafficWasServed() {
		StepVerifier.create(this.routeMetricsSource.collect())
			.assertNext((
					snapshot) -> assertThat(snapshot).isNotNull().extracting(RouteMetricsSnapshot::metrics).isNotNull())
			.verifyComplete();
	}

	/**
	 * The endpoint the siblings are polled on belongs to the discovery source, so it only
	 * exists once that provider is selected. It answers with this instance's own figures,
	 * never the consolidated ones &mdash; the base case that keeps instances from polling
	 * each other forever.
	 */
	@Test
	void shouldNotServeTheLocalEndpointWithoutTheDiscoveryProvider() {
		this.webTestClient.get().uri("/ui/metrics/local").exchange().expectStatus().isNotFound();
	}

}
