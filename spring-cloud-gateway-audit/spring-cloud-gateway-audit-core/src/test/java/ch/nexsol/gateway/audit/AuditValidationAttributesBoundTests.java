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

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that what the OpenAPI validation plugin reports is audited within a bound. Its
 * error text grows with the number of violations, and one body breaking the contract in
 * every element makes megabytes of it: copied as it is, it would be megabytes in every
 * event, serialised and sent to the backend for each such request.
 */
class AuditValidationAttributesBoundTests {

	private static final String VALIDATION_ATTRIBUTES_ATTR = "gatewayOpenapiValidationAttributes";

	private final AuditEventFactory factory = new AuditEventFactory(new AuditProperties());

	@Test
	void boundsAnErrorTextThatGrewWithTheViolations() {
		String errors = "items[0]: integer found, object expected; ".repeat(50_000);

		String audited = audited(Map.of("validation.request.errors", errors)).get("validation.request.errors");

		assertThat(audited).hasSizeLessThan(5_000);
		assertThat(audited).startsWith("items[0]: integer found, object expected; ");
		// What was cut is said, so a truncated value is never taken for the whole of it.
		assertThat(audited).contains("more characters");
	}

	@Test
	void leavesAnOrdinaryValueAsItIs() {
		Map<String, String> audited = audited(
				Map.of("validation.request.valid", "false", "validation.request.errors", "page: string found"));

		assertThat(audited).containsEntry("validation.request.valid", "false")
			.containsEntry("validation.request.errors", "page: string found");
	}

	private Map<String, String> audited(Map<String, String> validation) {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/book").build());
		exchange.getAttributes().put(VALIDATION_ATTRIBUTES_ATTR, validation);
		return this.factory.create(exchange).block().attributes();
	}

}
