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
import java.util.List;

import ch.nexsol.gateway.pentest.core.model.Finding;
import ch.nexsol.gateway.pentest.core.model.OwaspCategory;
import ch.nexsol.gateway.pentest.core.model.Severity;
import ch.nexsol.gateway.pentest.core.store.InMemoryFindingStore;
import ch.nexsol.gateway.pentest.passive.score.RouteScoreService;
import org.junit.jupiter.api.Test;

import org.springframework.test.web.reactive.server.WebTestClient;

class PassiveScanViewControllerTests {

	private WebTestClient client(InMemoryFindingStore store) {
		return WebTestClient
			.bindToController(new PassiveScanViewController(store, new RouteScoreService(List.of(), store)))
			.build();
	}

	private static Finding finding(Severity severity) {
		return new Finding("cors", OwaspCategory.API8_SECURITY_MISCONFIGURATION, "CWE-942", severity, "title", "detail",
				"fix", "GET", "/api/x", "route-x", Instant.now(), null);
	}

	@Test
	void servesFindingsAndSummary() {
		InMemoryFindingStore store = new InMemoryFindingStore(100);
		store.record(finding(Severity.CRITICAL));
		WebTestClient client = client(store);

		client.get()
			.uri("/ui/passive-scan/findings")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.length()")
			.isEqualTo(1)
			.jsonPath("$[0].severity")
			.isEqualTo("CRITICAL")
			.jsonPath("$[0].categoryCode")
			.isEqualTo("API8:2023");

		client.get()
			.uri("/ui/passive-scan/summary")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.total")
			.isEqualTo(1);
	}

	@Test
	void filtersBySeverity() {
		InMemoryFindingStore store = new InMemoryFindingStore(100);
		store.record(finding(Severity.CRITICAL));
		client(store).get()
			.uri("/ui/passive-scan/findings?severity=LOW")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.length()")
			.isEqualTo(0);
	}

}
