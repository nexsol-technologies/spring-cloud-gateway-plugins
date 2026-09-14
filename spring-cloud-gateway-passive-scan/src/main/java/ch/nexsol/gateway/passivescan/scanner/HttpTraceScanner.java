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
 * Flags a {@code TRACE} or {@code TRACK} request the gateway answered successfully, which
 * enables Cross-Site Tracing.
 */
public class HttpTraceScanner extends AbstractPassiveScanner {

	@Override
	public String id() {
		return "http-trace";
	}

	@Override
	public List<Finding> inspect(HttpExchange exchange) {
		String method = exchange.method();
		boolean trace = "TRACE".equalsIgnoreCase(method) || "TRACK".equalsIgnoreCase(method);
		if (!trace || exchange.status() == null || !exchange.status().is2xxSuccessful()) {
			return List.of();
		}
		return List.of(finding(exchange, OwaspCategory.API8_SECURITY_MISCONFIGURATION, Severity.MEDIUM,
				"HTTP " + method + " enabled", "The gateway answered an HTTP " + method + " request successfully.",
				Map.of("method", method)));
	}

}
