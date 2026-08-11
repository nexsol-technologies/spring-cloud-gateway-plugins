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

package ch.nexsol.gateway.ui.metrics;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link InstanceMetricsController}.
 */
@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class InstanceMetricsControllerTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldRenderInstancesPageWithinTheShell() {
		this.webTestClient.get()
			.uri("/ui/metrics/instances")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_HTML)
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("gw-sidebar")
				.contains("id=\"gi-instances\"")
				.contains("id=\"gi-coverage\"")
				.contains(">Instances</span>"));
	}

	@Test
	void shouldReturnTheInstanceFiguresAsJson() {
		// Read against the JVM binders Spring Boot registers itself rather than against
		// planted gauges, so the meter names this source looks up are the ones actually
		// published. Only the shape can be asserted: the values are those of the JVM
		// running the test.
		this.webTestClient.get()
			.uri("/ui/metrics/instances/data")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.instances.length()")
			.isEqualTo(1)
			.jsonPath("$.instances[0].jvm.heapUsedBytes")
			.value((Number heap) -> assertThat(heap.longValue()).isPositive())
			.jsonPath("$.instances[0].jvm.threadsLive")
			.value((Number threads) -> assertThat(threads.intValue()).isPositive())
			.jsonPath("$.instances[0].uptimeSeconds")
			.value((Number uptime) -> assertThat(uptime.longValue()).isNotNegative());
	}

	@Test
	void shouldStateWhatTheFiguresCover() {
		// Without a provider module the view only ever sees the instance that answered,
		// and the payload says so rather than passing it off as the whole gateway.
		this.webTestClient.get()
			.uri("/ui/metrics/instances/data")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.coverage")
			.value((String coverage) -> assertThat(coverage).contains("this instance only"));
	}

	@Test
	void shouldCarryTheRoutesBehindTheDownstreamAddresses() {
		// The map travels even when it is empty: the view reads it on every render, and
		// an absent field would leave every pool unnamed rather than un-nameable.
		this.webTestClient.get()
			.uri("/ui/metrics/instances/data")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.routesByAddress")
			.exists();
	}

	@Test
	void shouldReportWhichInstrumentationIsOff() {
		// Neither switch is on in this context, and the payload has to carry that: an
		// empty pool list alone would read as "no downstream called yet".
		this.webTestClient.get()
			.uri("/ui/metrics/instances/data")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.instances[0].instrumentation.connectionPool")
			.isEqualTo(false)
			.jsonPath("$.instances[0].instrumentation.httpClient")
			.isEqualTo(false)
			.jsonPath("$.instances[0].pools.length()")
			.isEqualTo(0);
	}

}
