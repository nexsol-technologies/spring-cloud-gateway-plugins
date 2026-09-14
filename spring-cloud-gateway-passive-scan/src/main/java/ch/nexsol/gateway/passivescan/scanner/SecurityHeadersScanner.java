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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.passivescan.model.Finding;
import ch.nexsol.gateway.passivescan.model.HttpExchange;
import ch.nexsol.gateway.passivescan.model.OwaspCategory;
import ch.nexsol.gateway.passivescan.model.Severity;

import org.springframework.http.HttpHeaders;

/**
 * Flags responses missing the baseline browser-protection headers.
 */
public class SecurityHeadersScanner extends AbstractPassiveScanner {

	@Override
	public String id() {
		return "security-headers";
	}

	@Override
	public List<Finding> inspect(HttpExchange exchange) {
		if (exchange.status() == null) {
			return List.of();
		}
		HttpHeaders headers = exchange.responseHeaders();
		List<String> missing = new ArrayList<>();
		if (headers.getFirst("X-Content-Type-Options") == null) {
			missing.add("X-Content-Type-Options");
		}
		if (headers.getFirst("Content-Security-Policy") == null && headers.getFirst("X-Frame-Options") == null) {
			missing.add("Content-Security-Policy or X-Frame-Options");
		}
		if (effectiveSecure(exchange) && headers.getFirst("Strict-Transport-Security") == null) {
			missing.add("Strict-Transport-Security");
		}
		if (missing.isEmpty()) {
			return List.of();
		}
		return List.of(finding(exchange, OwaspCategory.API8_SECURITY_MISCONFIGURATION, Severity.MEDIUM,
				"Missing security headers",
				"The response omits recommended security headers: " + String.join(", ", missing),
				Map.of("missing", String.join(", ", missing))));
	}

	private boolean effectiveSecure(HttpExchange exchange) {
		if (exchange.secure()) {
			return true;
		}
		return "https".equalsIgnoreCase(exchange.requestHeaders().getFirst("X-Forwarded-Proto"));
	}

}
