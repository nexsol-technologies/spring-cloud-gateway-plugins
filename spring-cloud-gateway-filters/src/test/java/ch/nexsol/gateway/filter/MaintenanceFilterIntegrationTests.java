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

package ch.nexsol.gateway.filter;

import ch.nexsol.gateway.filter.factory.MaintenanceGatewayFilterFactory;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Verifies the maintenance filter end to end: the window, the message and the bounds come
 * from the route configuration, and the non standard status reaches the caller as it is
 * configured rather than being rewritten to a status the HTTP layer knows.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext
@ActiveProfiles(profiles = "maintenance")
class MaintenanceFilterIntegrationTests extends BaseWebClientTests {

	@Test
	void shouldAnswerTheMaintenanceBodyWhileTheWindowIsOpen() {
		this.testClient.get()
			.uri("/maintenance-open")
			.header("Host", "www.maintenance.ch")
			.exchange()
			.expectStatus()
			.isEqualTo(MaintenanceGatewayFilterFactory.DEFAULT_STATUS)
			.expectHeader()
			.contentType(MediaType.APPLICATION_JSON)
			.expectHeader()
			.valueEquals(HttpHeaders.RETRY_AFTER, "Sun, 02 Sep 2125 02:00:00 GMT")
			.expectBody()
			.jsonPath("$.message")
			.isEqualTo("The shop is closed until 4am.")
			.jsonPath("$.start")
			.isEqualTo("2025-09-01T22:00:00Z")
			.jsonPath("$.end")
			.isEqualTo("2125-09-02T02:00:00Z");
	}

	@Test
	void shouldForwardWhileTheWindowIsStillAhead() {
		this.testClient.get()
			.uri("/maintenance-planned")
			.header("Host", "www.maintenance.ch")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.reached")
			.isEqualTo(true);
	}

}
