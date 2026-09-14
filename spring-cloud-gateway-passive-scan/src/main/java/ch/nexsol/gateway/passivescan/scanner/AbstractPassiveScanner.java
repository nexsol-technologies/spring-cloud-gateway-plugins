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

import java.time.Instant;
import java.util.Map;

import ch.nexsol.gateway.passivescan.model.Finding;
import ch.nexsol.gateway.passivescan.model.HttpExchange;
import ch.nexsol.gateway.passivescan.model.OwaspCategory;
import ch.nexsol.gateway.passivescan.model.Severity;

/**
 * Base class factoring out the construction of a {@link Finding} from the exchange being
 * inspected.
 */
public abstract class AbstractPassiveScanner implements PassiveScanner {

	/**
	 * Build a finding stamped with this scanner's id and the coordinates of the exchange.
	 * @param exchange the exchange being inspected
	 * @param category the OWASP category
	 * @param severity the severity
	 * @param title the short title
	 * @param detail the explanation
	 * @param evidence the observed values
	 * @return the finding
	 */
	protected Finding finding(HttpExchange exchange, OwaspCategory category, Severity severity, String title,
			String detail, Map<String, String> evidence) {
		return new Finding(id(), category, severity, title, detail, exchange.method(), exchange.path(),
				exchange.routeId(), Instant.now(), evidence);
	}

}
