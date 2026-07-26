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
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.ui.overview.OverviewStat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditOverviewContributionTests {

	@Test
	void countsTheAuditedExchangesAndTheFailedOnes() {
		OverviewStat stat = AuditOverviewContribution.toStat(List.of(event(200), event(404), event(500), event(0)));

		assertThat(stat.label()).isEqualTo("Audited exchanges");
		assertThat(stat.value()).isEqualTo("4");
		assertThat(stat.detail()).startsWith("2 failed");
	}

	@Test
	void saysSoWhenNothingWasAuditedYet() {
		assertThat(AuditOverviewContribution.toStat(List.of()).detail()).isEqualTo("nothing audited yet");
	}

	private static AuditEventView event(int statusCode) {
		return new AuditEventView(Instant.now(), "GET", "/api", String.valueOf(statusCode), statusCode, null, null,
				null, Map.of());
	}

}
