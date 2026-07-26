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
import java.util.Map;

import ch.nexsol.gateway.audit.AuditAttributes;
import ch.nexsol.gateway.audit.AuditEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventViewTests {

	@Test
	void liftsTheAttributesWorthAColumnOfTheirOwn() {
		Map<String, String> attributes = new LinkedHashMap<>();
		attributes.put(AuditAttributes.REQUEST_METHOD, "POST");
		attributes.put(AuditAttributes.REQUEST_PATH, "/api/orders");
		attributes.put(AuditAttributes.RESPONSE_STATUS, "CREATED");
		attributes.put(AuditAttributes.JWT_USER_ID, "alice");
		attributes.put(AuditAttributes.REQUEST_IP, "10.0.0.7");
		attributes.put(AuditAttributes.TRACE_ID, "abcdef");
		Instant capturedAt = Instant.parse("2025-07-26T10:15:30Z");

		AuditEventView view = AuditEventView.of(new AuditEvent(capturedAt, attributes));

		assertThat(view.timestamp()).isEqualTo(capturedAt);
		assertThat(view.method()).isEqualTo("POST");
		assertThat(view.path()).isEqualTo("/api/orders");
		assertThat(view.status()).isEqualTo("CREATED");
		assertThat(view.statusCode()).isEqualTo(201);
		assertThat(view.user()).isEqualTo("alice");
		assertThat(view.ip()).isEqualTo("10.0.0.7");
		assertThat(view.traceId()).isEqualTo("abcdef");
		assertThat(view.attributes()).containsAllEntriesOf(attributes);
	}

	@Test
	void leavesTheColumnsEmptyWhenTheAuditGroupsCollectedNothing() {
		AuditEventView view = AuditEventView.of(new AuditEvent(Instant.now(), Map.of()));

		assertThat(view.method()).isNull();
		assertThat(view.path()).isNull();
		assertThat(view.statusCode()).isZero();
		assertThat(view.attributes()).isEmpty();
	}

	@Test
	void resolvesTheStatusFromItsNameOrItsNumber() {
		assertThat(AuditEventView.statusCode("NOT_FOUND")).isEqualTo(404);
		assertThat(AuditEventView.statusCode("503")).isEqualTo(503);
	}

	@Test
	void reportsNoStatusWhenItCannotBeResolved() {
		assertThat(AuditEventView.statusCode(null)).isZero();
		assertThat(AuditEventView.statusCode("")).isZero();
		assertThat(AuditEventView.statusCode(AuditAttributes.NONE_VALUE)).isZero();
		assertThat(AuditEventView.statusCode("NOT_A_STATUS")).isZero();
	}

}
