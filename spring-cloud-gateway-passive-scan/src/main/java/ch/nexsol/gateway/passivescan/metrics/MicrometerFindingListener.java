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

package ch.nexsol.gateway.passivescan.metrics;

import ch.nexsol.gateway.passivescan.engine.FindingListener;
import ch.nexsol.gateway.passivescan.model.Finding;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counts each finding through Micrometer, tagged by severity and OWASP category, so the
 * metrics plugin and its dashboards pick the figures up like any other meter.
 */
public class MicrometerFindingListener implements FindingListener {

	private static final String METER = "gateway.passive.scan.findings";

	private final MeterRegistry registry;

	public MicrometerFindingListener(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void onFinding(Finding finding) {
		this.registry.counter(METER, "severity", finding.severity().name(), "category", finding.category().name())
			.increment();
	}

}
