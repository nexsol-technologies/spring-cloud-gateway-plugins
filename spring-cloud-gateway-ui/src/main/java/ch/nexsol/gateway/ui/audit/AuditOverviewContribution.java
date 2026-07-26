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

import java.util.List;

import ch.nexsol.gateway.ui.overview.OverviewContribution;
import ch.nexsol.gateway.ui.overview.OverviewStat;
import reactor.core.publisher.Flux;

/**
 * Contributes the audit figures to the home page: how many exchanges are held in the tail
 * and how many of them failed.
 */
public class AuditOverviewContribution implements OverviewContribution {

	private final AuditTailBuffer buffer;

	/**
	 * Creates the contribution over the audit tail.
	 * @param buffer the buffer holding the recent audit events
	 */
	public AuditOverviewContribution(AuditTailBuffer buffer) {
		this.buffer = buffer;
	}

	@Override
	public Flux<OverviewStat> stats() {
		return Flux.defer(() -> Flux.just(toStat(this.buffer.snapshot())));
	}

	/**
	 * Turns the buffered events into the audit figure shown on the home page.
	 * @param events the buffered events
	 * @return the contributed figure
	 */
	static OverviewStat toStat(List<AuditEventView> events) {
		long failed = events.stream().filter((event) -> event.statusCode() >= 400).count();
		String detail = events.isEmpty() ? "nothing audited yet"
				: failed + " failed, kept in memory (max " + AuditTailBuffer.CAPACITY + ")";
		return new OverviewStat("Audited exchanges", String.valueOf(events.size()), detail, 50);
	}

}
