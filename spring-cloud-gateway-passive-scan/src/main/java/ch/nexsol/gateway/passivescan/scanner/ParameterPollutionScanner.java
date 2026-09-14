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

/**
 * Flags requests that repeat a query parameter, which back-ends behind the gateway may
 * resolve inconsistently (HTTP parameter pollution).
 */
public class ParameterPollutionScanner extends AbstractPassiveScanner {

	@Override
	public String id() {
		return "parameter-pollution";
	}

	@Override
	public List<Finding> inspect(HttpExchange exchange) {
		List<String> repeated = new ArrayList<>();
		exchange.queryParams().forEach((name, values) -> {
			if (values.size() > 1) {
				repeated.add(name);
			}
		});
		if (repeated.isEmpty()) {
			return List.of();
		}
		return List.of(finding(exchange, OwaspCategory.API8_SECURITY_MISCONFIGURATION, Severity.LOW,
				"HTTP parameter pollution", "The request repeats query parameters: " + String.join(", ", repeated),
				Map.of("parameters", String.join(", ", repeated))));
	}

}
