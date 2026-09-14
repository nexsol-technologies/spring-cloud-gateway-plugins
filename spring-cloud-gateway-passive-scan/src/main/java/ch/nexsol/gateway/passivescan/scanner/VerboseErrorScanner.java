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

package ch.nexsol.gateway.passivescan.scanner;

import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.passivescan.model.Finding;
import ch.nexsol.gateway.passivescan.model.HttpExchange;
import ch.nexsol.gateway.passivescan.model.OwaspCategory;
import ch.nexsol.gateway.passivescan.model.Severity;

/**
 * Flags responses whose body leaks a stack trace or internal error detail. Requires
 * response body capture to be enabled; a no-op otherwise.
 */
public class VerboseErrorScanner extends AbstractPassiveScanner {

	private static final List<String> SIGNATURES = List.of("Exception", "at java.", "at org.", "at com.",
			"Traceback (most recent call last)", "SQLException", "SQLSyntaxError", "ORA-", "stack trace",
			"org.springframework");

	@Override
	public String id() {
		return "verbose-error";
	}

	@Override
	public List<Finding> inspect(HttpExchange exchange) {
		String body = exchange.responseBody();
		if (body == null || body.isEmpty()) {
			return List.of();
		}
		for (String signature : SIGNATURES) {
			if (body.contains(signature)) {
				boolean serverError = exchange.status() != null && exchange.status().is5xxServerError();
				return List.of(finding(exchange, OwaspCategory.API8_SECURITY_MISCONFIGURATION,
						serverError ? Severity.HIGH : Severity.MEDIUM, "Verbose error response",
						"The response body discloses internal error detail.", Map.of("signature", signature)));
			}
		}
		return List.of();
	}

}
