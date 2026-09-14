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

package ch.nexsol.gateway.ui.passivescan;

import java.time.Instant;
import java.util.Map;

import ch.nexsol.gateway.pentest.core.model.AggregatedFinding;
import ch.nexsol.gateway.pentest.core.model.Finding;

/**
 * The projection of an aggregated passive-scan {@link Finding} the console renders,
 * decoupled from the plugin's enum names and carrying the occurrence count.
 *
 * @param lastSeen when the finding was last observed
 * @param firstSeen when the finding was first observed
 * @param count how many times it was observed
 * @param severity the severity name
 * @param categoryCode the OWASP code, e.g. {@code API8:2023}
 * @param categoryTitle the OWASP title
 * @param cwe the CWE identifier
 * @param scanner the scanner id
 * @param method the HTTP method
 * @param path the request path
 * @param title the short finding title
 * @param detail what was observed and why it matters
 * @param remediation how to fix it
 * @param routeId the route id, or {@code null}
 * @param evidence the observed values
 */
public record FindingView(Instant lastSeen, Instant firstSeen, long count, String severity, String categoryCode,
		String categoryTitle, String cwe, String scanner, String method, String path, String title, String detail,
		String remediation, String routeId, Map<String, String> evidence) {

	static FindingView of(AggregatedFinding aggregate) {
		Finding finding = aggregate.finding();
		return new FindingView(aggregate.lastSeen(), aggregate.firstSeen(), aggregate.count(),
				finding.severity().name(), finding.category().getCode(), finding.category().getTitle(), finding.cwe(),
				finding.scannerId(), finding.method(), finding.path(), finding.title(), finding.detail(),
				finding.remediation(), finding.routeId(), finding.evidence());
	}

}
