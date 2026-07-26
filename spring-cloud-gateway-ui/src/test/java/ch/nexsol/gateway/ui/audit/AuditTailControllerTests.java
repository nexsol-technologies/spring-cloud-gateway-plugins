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

package ch.nexsol.gateway.ui.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.audit.AuditAttributes;
import ch.nexsol.gateway.audit.AuditEvent;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The buffered tail is a singleton shared by every test of this class, so the tests going
 * through HTTP only assert on the newest events. The filtering is covered against a
 * buffer of their own.
 */
@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class AuditTailControllerTests {

	@Autowired
	WebTestClient webTestClient;

	@Autowired
	AuditTailBuffer sharedBuffer;

	@Test
	void shouldRenderAuditPageWithinTheShell() {
		this.webTestClient.get()
			.uri("/ui/audit")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_HTML)
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("gw-sidebar")
				.contains("id=\"ga-tbody\"")
				.contains("id=\"ga-live\"")
				.contains(String.valueOf(AuditTailBuffer.CAPACITY))
				.contains(">Audit</span>"));
	}

	@Test
	void shouldReturnTheNewestEventAsJsonWithItsStatusResolved() {
		this.sharedBuffer.record(event("POST", "/api/payments", "INTERNAL_SERVER_ERROR", "bob", "10.0.0.2"));

		this.webTestClient.get()
			.uri("/ui/audit/events?limit=1")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.length()")
			.isEqualTo(1)
			.jsonPath("$[0].method")
			.isEqualTo("POST")
			.jsonPath("$[0].path")
			.isEqualTo("/api/payments")
			.jsonPath("$[0].status")
			.isEqualTo("INTERNAL_SERVER_ERROR")
			.jsonPath("$[0].statusCode")
			.isEqualTo(500)
			.jsonPath("$[0].user")
			.isEqualTo("bob")
			.jsonPath("$[0].attributes['request.ip']")
			.isEqualTo("10.0.0.2");
	}

	@Test
	void shouldReturnAnEmptyListRatherThanFailWhenNothingMatches() {
		this.webTestClient.get()
			.uri("/ui/audit/events?query=nothing-like-this-can-exist")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.length()")
			.isEqualTo(0);
	}

	@Test
	void keepsOnlyTheRequestedStatusClass() {
		AuditTailController controller = controllerOver(event("GET", "/api/orders", "OK", "alice", "10.0.0.1"),
				event("POST", "/api/payments", "INTERNAL_SERVER_ERROR", "bob", "10.0.0.2"),
				event("GET", "/api/missing", "NOT_FOUND", "alice", "10.0.0.1"));

		assertThat(controller.events("5xx", null, 100)).extracting(AuditEventView::path)
			.containsExactly("/api/payments");
		assertThat(controller.events("4xx", null, 100)).extracting(AuditEventView::path)
			.containsExactly("/api/missing");
		assertThat(controller.events(null, null, 100)).hasSize(3);
	}

	@Test
	void dropsTheEventsWhoseStatusIsUnknownAsSoonAsAClassIsRequested() {
		AuditTailController controller = controllerOver(
				event("GET", "/api/orders", AuditAttributes.NONE_VALUE, "alice", "10.0.0.1"));

		assertThat(controller.events("2xx", null, 100)).isEmpty();
		assertThat(controller.events("", null, 100)).hasSize(1);
	}

	@Test
	void searchesAcrossTheFieldsTheViewShows() {
		AuditTailController controller = controllerOver(event("GET", "/api/orders", "OK", "alice", "10.0.0.1"),
				event("POST", "/api/payments", "OK", "bob", "10.0.0.2"));

		assertThat(controller.events(null, "ALICE", 100)).hasSize(1);
		assertThat(controller.events(null, "payments", 100)).hasSize(1);
		assertThat(controller.events(null, "10.0.0.", 100)).hasSize(2);
		assertThat(controller.events(null, "post", 100)).hasSize(1);
		assertThat(controller.events(null, "  ", 100)).hasSize(2);
	}

	@Test
	void clampsTheRequestedLimitToWhatTheBufferCanHold() {
		AuditTailController controller = controllerOver(event("GET", "/a", "OK", "alice", "10.0.0.1"),
				event("GET", "/b", "OK", "alice", "10.0.0.1"));

		assertThat(controller.events(null, null, 1)).hasSize(1);
		assertThat(controller.events(null, null, Integer.MAX_VALUE)).hasSize(2);
		assertThat(controller.events(null, null, 0)).hasSize(1);
		assertThat(controller.events(null, null, -5)).hasSize(1);
	}

	private static AuditTailController controllerOver(AuditEvent... events) {
		AuditTailBuffer buffer = new AuditTailBuffer();
		List.of(events).forEach(buffer::record);
		return new AuditTailController(buffer);
	}

	private static AuditEvent event(String method, String path, String status, String user, String ip) {
		Map<String, String> attributes = new LinkedHashMap<>();
		attributes.put(AuditAttributes.REQUEST_METHOD, method);
		attributes.put(AuditAttributes.REQUEST_PATH, path);
		attributes.put(AuditAttributes.RESPONSE_STATUS, status);
		attributes.put(AuditAttributes.JWT_USER_ID, user);
		attributes.put(AuditAttributes.REQUEST_IP, ip);
		return new AuditEvent(Instant.now(), attributes);
	}

}
