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

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class RouteMetricsControllerTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldRenderTrafficPageWithinTheShell() {
		this.webTestClient.get()
			.uri("/ui/metrics")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_HTML)
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("gw-sidebar")
				.contains("id=\"gm-chart\"")
				.contains("id=\"gm-tbody\"")
				.contains("Where should I optimise?")
				.contains(">Traffic</span>"));
	}

	@Test
	void shouldReturnAggregatedMetricsAsJson() {
		this.webTestClient.get()
			.uri("/ui/metrics/data")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$[0].routeId")
			.isEqualTo("alpha")
			.jsonPath("$[0].count")
			.isEqualTo(1);
	}

	@TestConfiguration
	static class MetricsTestConfiguration {

		@Bean
		MeterRegistry meterRegistry() {
			SimpleMeterRegistry registry = new SimpleMeterRegistry();
			Timer.builder(RouteMetricsService.REQUESTS_METER)
				.tags("routeId", "alpha", "routeUri", "http://alpha", "httpStatusCode", "200")
				.register(registry)
				.record(50, TimeUnit.MILLISECONDS);
			return registry;
		}

	}

}
