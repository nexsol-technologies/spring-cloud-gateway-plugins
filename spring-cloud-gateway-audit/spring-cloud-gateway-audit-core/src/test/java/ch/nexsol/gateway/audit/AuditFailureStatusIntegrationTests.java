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

package ch.nexsol.gateway.audit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Tests the status audited for a failed exchange on a running server. A mock response
 * reports no status until one is written; the response of Reactor Netty reports
 * {@code 200}, which is what turned every rejected exchange into an audited success.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, classes = AuditFailureStatusIntegrationTests.Application.class,
		properties = { "spring.cloud.gateway.server.webflux.audit.web-filter.enabled=true",
				"spring.cloud.gateway.server.webflux.routes[0].id=unreachable",
				"spring.cloud.gateway.server.webflux.routes[0].uri=http://localhost:1",
				"spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/unreachable/**" })
class AuditFailureStatusIntegrationTests {

	@LocalServerPort
	int port;

	@Autowired
	RecordingPublisher publisher;

	@BeforeEach
	void forget() {
		this.publisher.events.clear();
	}

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void auditsNotFoundForARequestNoRouteServes() {
		client().get().uri("/nowhere").exchange().expectStatus().isNotFound();

		assertThat(this.publisher.events).singleElement()
			.satisfies((event) -> assertThat(event.attributes()).containsEntry(AuditAttributes.RESPONSE_STATUS,
					"NOT_FOUND"));
	}

	@Test
	void auditsAServerErrorForAnUpstreamThatRefusesTheConnection() {
		client().get().uri("/unreachable/book").exchange().expectStatus().is5xxServerError();

		assertThat(this.publisher.events).singleElement()
			.satisfies((event) -> assertThat(event.attributes()).containsEntry(AuditAttributes.RESPONSE_STATUS,
					"INTERNAL_SERVER_ERROR"));
	}

	/**
	 * A gateway auditing every exchange, with one route to an upstream nobody listens on.
	 */
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class Application {

		@Bean
		RecordingPublisher recordingPublisher() {
			return new RecordingPublisher();
		}

	}

	static class RecordingPublisher implements AuditEventPublisher {

		final List<AuditEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void publish(AuditEvent event) {
			this.events.add(event);
		}

	}

}
