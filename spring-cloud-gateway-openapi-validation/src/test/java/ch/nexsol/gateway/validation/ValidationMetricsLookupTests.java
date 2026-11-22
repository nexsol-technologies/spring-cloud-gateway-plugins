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

package ch.nexsol.gateway.validation;

import java.util.concurrent.atomic.AtomicInteger;

import ch.nexsol.gateway.validation.ValidationMetrics.SkipReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that counting a message does not look its counter up again each time: a lookup
 * builds the tags and the id of the meter before it finds it, for every request and every
 * response.
 */
class ValidationMetricsLookupTests {

	private final CountingRegistry registry = new CountingRegistry();

	private final ValidationMetrics metrics = new ValidationMetrics(this.registry);

	@Test
	void looksTheCounterOfAnOutcomeUpOnce() {
		for (int message = 0; message < 5; message++) {
			this.metrics.validated("request", "books", "GET /books", ValidationMode.ENFORCE, true);
		}

		assertThat(this.registry.lookups).hasValue(1);
		assertThat(this.registry.get(ValidationMetrics.VALIDATIONS).tag("outcome", "valid").counter().count())
			.isEqualTo(5);
	}

	@Test
	void keepsTheOutcomesApart() {
		this.metrics.validated("request", "books", "GET /books", ValidationMode.ENFORCE, true);
		this.metrics.validated("request", "books", "GET /books", ValidationMode.ENFORCE, false);
		this.metrics.validated("response", "books", "GET /books", ValidationMode.REPORT, false);
		this.metrics.validated("request", "orders", "GET /books", ValidationMode.ENFORCE, false);
		this.metrics.validated("request", "books", "POST /books", ValidationMode.ENFORCE, false);
		this.metrics.validated("request", "books", "GET /books", ValidationMode.ENFORCE, false);

		assertThat(this.registry.get(ValidationMetrics.VALIDATIONS).counters()).hasSize(5);
		assertThat(this.registry.get(ValidationMetrics.VALIDATIONS)
			.tags("direction", "request", "route", "books", "operation", "GET /books", "mode", "ENFORCE", "outcome",
					"invalid")
			.counter()
			.count()).isEqualTo(2);
	}

	@Test
	void looksTheCounterOfASkippedBodyUpOnce() {
		for (int message = 0; message < 5; message++) {
			this.metrics.bodySkipped("response", "books", SkipReason.ENCODED);
		}
		this.metrics.bodySkipped("response", "books", SkipReason.TOO_LARGE);

		assertThat(this.registry.lookups).hasValue(2);
		assertThat(this.registry.get(ValidationMetrics.BODIES_SKIPPED).tag("reason", "encoded").counter().count())
			.isEqualTo(5);
	}

	@Test
	void looksTheCounterOfAnUnavailableContractUpOnce() {
		for (int message = 0; message < 5; message++) {
			this.metrics.contractUnavailable("classpath:none.yaml");
		}

		assertThat(this.registry.lookups).hasValue(1);
		assertThat(this.registry.get(ValidationMetrics.CONTRACTS_UNAVAILABLE).counter().count()).isEqualTo(5);
	}

	@Test
	void recordsNothingWithoutARegistry() {
		ValidationMetrics silent = new ValidationMetrics(null);

		silent.validated("request", "books", "GET /books", ValidationMode.ENFORCE, true);
		silent.bodySkipped("response", "books", SkipReason.ENCODED);
		silent.contractUnavailable("classpath:none.yaml");

		assertThat(this.registry.lookups).hasValue(0);
	}

	static class CountingRegistry extends SimpleMeterRegistry {

		final AtomicInteger lookups = new AtomicInteger();

		@Override
		public Counter counter(String name, Tags tags) {
			this.lookups.incrementAndGet();
			return super.counter(name, tags);
		}

	}

}
