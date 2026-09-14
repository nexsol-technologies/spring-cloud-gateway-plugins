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

import org.springframework.http.HttpHeaders;

/**
 * Flags permissive CORS responses, most seriously a wildcard or reflected origin combined
 * with credentials.
 */
public class CorsScanner extends AbstractPassiveScanner {

	@Override
	public String id() {
		return "cors";
	}

	@Override
	public List<Finding> inspect(HttpExchange exchange) {
		String acao = exchange.responseHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
		if (acao == null) {
			return List.of();
		}
		String origin = exchange.requestHeaders().getFirst(HttpHeaders.ORIGIN);
		boolean credentials = "true"
			.equalsIgnoreCase(exchange.responseHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
		Map<String, String> evidence = Map.of("allow-origin", acao, "allow-credentials", String.valueOf(credentials),
				"request-origin", String.valueOf(origin));
		if ("*".equals(acao) && credentials) {
			return List.of(finding(exchange, OwaspCategory.API8_SECURITY_MISCONFIGURATION, Severity.CRITICAL,
					"CORS wildcard with credentials", "The response allows any origin while permitting credentials.",
					evidence));
		}
		if (origin != null && acao.equals(origin) && credentials) {
			return List.of(finding(exchange, OwaspCategory.API8_SECURITY_MISCONFIGURATION, Severity.HIGH,
					"CORS reflected origin with credentials",
					"The response reflects the request origin while permitting credentials.", evidence));
		}
		if ("*".equals(acao)) {
			return List.of(finding(exchange, OwaspCategory.API8_SECURITY_MISCONFIGURATION, Severity.LOW,
					"Permissive CORS", "The response allows any origin.", evidence));
		}
		if (origin != null && acao.equals(origin)) {
			return List.of(finding(exchange, OwaspCategory.API8_SECURITY_MISCONFIGURATION, Severity.LOW,
					"CORS reflects request origin",
					"The response reflects the request origin; confirm the origin is validated.", evidence));
		}
		return List.of();
	}

}
