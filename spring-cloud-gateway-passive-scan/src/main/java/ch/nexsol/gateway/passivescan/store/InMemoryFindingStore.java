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

package ch.nexsol.gateway.passivescan.store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import ch.nexsol.gateway.passivescan.model.Finding;
import ch.nexsol.gateway.passivescan.model.OwaspCategory;
import ch.nexsol.gateway.passivescan.model.Severity;

/**
 * In-memory {@link FindingStore} keeping the last {@code maxFindings} findings in a
 * bounded ring buffer, plus cumulative per-severity and per-category counters that
 * survive the eviction of the findings they counted.
 */
public class InMemoryFindingStore implements FindingStore {

	private final int maxFindings;

	private final Deque<Finding> buffer;

	private final Map<Severity, LongAdder> bySeverity = new EnumMap<>(Severity.class);

	private final Map<OwaspCategory, LongAdder> byCategory = new EnumMap<>(OwaspCategory.class);

	private final AtomicLong total = new AtomicLong();

	public InMemoryFindingStore(int maxFindings) {
		this.maxFindings = maxFindings;
		this.buffer = new ArrayDeque<>(maxFindings);
		for (Severity severity : Severity.values()) {
			this.bySeverity.put(severity, new LongAdder());
		}
		for (OwaspCategory category : OwaspCategory.values()) {
			this.byCategory.put(category, new LongAdder());
		}
	}

	@Override
	public synchronized void record(Finding finding) {
		if (this.buffer.size() >= this.maxFindings) {
			this.buffer.removeLast();
		}
		this.buffer.addFirst(finding);
		this.total.incrementAndGet();
		this.bySeverity.get(finding.severity()).increment();
		this.byCategory.get(finding.category()).increment();
	}

	@Override
	public synchronized List<Finding> recent() {
		return Collections.unmodifiableList(new ArrayList<>(this.buffer));
	}

	@Override
	public FindingSummary summary() {
		Map<Severity, Long> severities = new EnumMap<>(Severity.class);
		this.bySeverity.forEach((key, value) -> severities.put(key, value.sum()));
		Map<OwaspCategory, Long> categories = new EnumMap<>(OwaspCategory.class);
		this.byCategory.forEach((key, value) -> categories.put(key, value.sum()));
		return new FindingSummary(this.total.get(), severities, categories);
	}

	@Override
	public synchronized void clear() {
		this.buffer.clear();
		this.total.set(0);
		this.bySeverity.values().forEach(LongAdder::reset);
		this.byCategory.values().forEach(LongAdder::reset);
	}

}
