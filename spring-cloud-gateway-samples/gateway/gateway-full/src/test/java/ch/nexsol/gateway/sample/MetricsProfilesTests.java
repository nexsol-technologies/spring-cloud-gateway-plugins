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

package ch.nexsol.gateway.sample;

import ch.nexsol.gateway.metrics.MetricsProperties;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import ch.nexsol.gateway.metrics.discovery.DiscoveryRouteMetricsSource;
import ch.nexsol.gateway.metrics.prometheus.PrometheusRouteMetricsSource;
import ch.nexsol.gateway.metrics.redis.RedisRouteMetricsSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that each of the three consolidating sources is the one selected under its
 * profile, and that nothing else in this dense sample gets in the way.
 * <p>
 * What is asserted is the wiring, not the readings: a source that consolidates needs the
 * backend it consolidates through, and starting a Redis or a Prometheus is what running
 * the sample is for. Neither is contacted while a context merely starts &mdash; the
 * sources read on demand, when a view refreshes.
 * <p>
 * The registry is off throughout, as in {@link GatewayApplicationTests}: what the
 * discovery source needs from it is exercised by the sample, not here.
 */
class MetricsProfilesTests {

	@Nested
	@SpringBootTest(properties = { "eureka.client.enabled=false",
			"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false" })
	@ActiveProfiles("metrics-redis")
	class RedisProfile {

		@Autowired
		RouteMetricsSource routeMetricsSource;

		@Autowired
		MetricsProperties properties;

		@Test
		void shouldPublishIntoRedisRatherThanReportingThisInstanceAlone() {
			assertThat(this.routeMetricsSource).isInstanceOf(RedisRouteMetricsSource.class);
			assertThat(this.properties.getInstanceId()).isEqualTo("gateway-full-1");
		}

	}

	@Nested
	@SpringBootTest(properties = { "eureka.client.enabled=false",
			"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false" })
	@ActiveProfiles({ "metrics-redis", "instance2" })
	class SecondInstance {

		@Autowired
		MetricsProperties properties;

		@Test
		void shouldTakeOverTheIdentityWhateverTheSourceProfileSets() {
			assertThat(this.properties.getInstanceId()).isEqualTo("gateway-full-2");
		}

	}

	@Nested
	@SpringBootTest(properties = { "eureka.client.enabled=false",
			"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false" })
	@ActiveProfiles("metrics-prometheus")
	class PrometheusProfile {

		@Autowired
		RouteMetricsSource routeMetricsSource;

		@Test
		void shouldReadTheConsolidatedSeriesFromPrometheus() {
			assertThat(this.routeMetricsSource).isInstanceOf(PrometheusRouteMetricsSource.class);
		}

	}

	@Nested
	@SpringBootTest(properties = { "eureka.client.enabled=false",
			"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false" })
	@ActiveProfiles("metrics-discovery")
	@AutoConfigureWebTestClient
	class DiscoveryProfile {

		@Autowired
		RouteMetricsSource routeMetricsSource;

		@Autowired
		WebTestClient webTestClient;

		@Test
		void shouldPollTheSiblingInstances() {
			assertThat(this.routeMetricsSource).isInstanceOf(DiscoveryRouteMetricsSource.class);
		}

		/**
		 * The endpoint the siblings poll, which this console being behind a login page
		 * would otherwise close: an instance answering a redirect to its siblings reports
		 * its own traffic and nothing else, and the coverage would quietly read one
		 * instance on a fleet of them.
		 */
		@Test
		void shouldLeaveTheLocalEndpointReachableWithoutAPrincipal() {
			this.webTestClient.get().uri("/ui/metrics/local").exchange().expectStatus().isOk();
			this.webTestClient.get().uri("/ui/metrics/local/instance").exchange().expectStatus().isOk();
		}

	}

}
