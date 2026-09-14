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

import java.util.List;

import ch.nexsol.gateway.passivescan.model.Finding;

/**
 * Holds the findings raised by the passive scanners. The default implementation keeps a
 * bounded window of the most recent findings in memory; a durable implementation may
 * override it by providing another bean of this type.
 */
public interface FindingStore {

	/**
	 * Record a finding.
	 * @param finding the finding to store
	 */
	void record(Finding finding);

	/**
	 * The most recent findings, newest first.
	 * @return the retained findings
	 */
	List<Finding> recent();

	/**
	 * The cumulative counts of every finding ever raised.
	 * @return the summary
	 */
	FindingSummary summary();

	/**
	 * Discard the retained findings and reset the cumulative counts.
	 */
	void clear();

}
