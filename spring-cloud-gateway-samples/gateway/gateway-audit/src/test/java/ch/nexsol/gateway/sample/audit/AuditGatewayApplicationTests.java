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

package ch.nexsol.gateway.sample.audit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.nexsol.gateway.audit.AuditApplicationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what the sample audits, and what it deliberately does not: the {@code Audit}
 * filter is declared per route, so an exchange no audited route handled publishes
 * nothing. The backends are never reached: the event is published once the exchange is
 * over, whether the upstream answered or refused the connection.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class AuditGatewayApplicationTests {

	@Autowired
	WebTestClient webTestClient;

	@Autowired
	CapturedEvents capturedEvents;

	@BeforeEach
	void setUp() {
		this.capturedEvents.clear();
	}

	@Test
	void shouldAuditARouteCarryingTheFilter() {
		this.webTestClient.get().uri("/audited/sample").exchange();

		assertThat(this.capturedEvents.attributes()).hasSize(1).first().satisfies((attributes) -> {
			assertThat(attributes).containsEntry("route.id", "audited_service_a");
			assertThat(attributes).containsEntry("request.method", "GET");
			assertThat(attributes).containsEntry("request.path", "/audited/sample");
		});
	}

	@Test
	void shouldAuditTheRouteMetadataAndTheGatewayMetadata() {
		this.webTestClient.get().uri("/audited/sample").exchange();

		assertThat(this.capturedEvents.attributes()).first().satisfies((attributes) -> {
			assertThat(attributes).containsEntry("route.metadata.tenant", "acme");
			assertThat(attributes).containsEntry("route.metadata.criticality", "high");
			assertThat(attributes).containsEntry("metadata.environment", "sample");
			assertThat(attributes).containsEntry("metadata.datacenter", "geneva");
		});
	}

	@Test
	void shouldNotAuditAnExchangeNoAuditedRouteHandled() {
		this.webTestClient.get().uri("/no-route-matches-this").exchange().expectStatus().isNotFound();

		assertThat(this.capturedEvents.attributes()).isEmpty();
	}

	@TestConfiguration
	static class CaptureConfiguration {

		@Bean
		CapturedEvents capturedEvents() {
			return new CapturedEvents();
		}

	}

	/**
	 * Collects what the default publisher republishes, which is how an application
	 * forwards audit events without replacing the publisher bean.
	 */
	static class CapturedEvents {

		private final List<Map<String, String>> attributes = new CopyOnWriteArrayList<>();

		@EventListener
		void on(AuditApplicationEvent event) {
			this.attributes.add(event.getAuditEvent().attributes());
		}

		List<Map<String, String>> attributes() {
			return this.attributes;
		}

		void clear() {
			this.attributes.clear();
		}

	}

}
