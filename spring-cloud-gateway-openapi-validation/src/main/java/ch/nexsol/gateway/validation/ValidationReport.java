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

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of validating one message against a contract: the list of the ways it breaks
 * that contract, empty when it honours it.
 *
 * @param errors the contract violations, in the order they were found
 */
public record ValidationReport(List<String> errors) {

	private static final ValidationReport EMPTY = new ValidationReport(List.of());

	/**
	 * Creates a report.
	 * @param errors the contract violations
	 */
	public ValidationReport {
		errors = List.copyOf(errors);
	}

	/**
	 * Returns the report of a message that honours its contract.
	 * @return an empty report
	 */
	public static ValidationReport empty() {
		return EMPTY;
	}

	/**
	 * Returns a report holding the given violations.
	 * @param errors the contract violations
	 * @return the report
	 */
	public static ValidationReport of(String... errors) {
		return new ValidationReport(List.of(errors));
	}

	/**
	 * Returns a report holding the violations of both reports.
	 * @param other the report to merge into this one
	 * @return the merged report
	 */
	public ValidationReport and(ValidationReport other) {
		if (other.isValid()) {
			return this;
		}
		if (isValid()) {
			return other;
		}
		List<String> merged = new ArrayList<>(this.errors);
		merged.addAll(other.errors);
		return new ValidationReport(merged);
	}

	/**
	 * Returns whether the message honours its contract.
	 * @return {@code true} when no violation was found
	 */
	public boolean isValid() {
		return this.errors.isEmpty();
	}

	/**
	 * Renders the violations as a single line, suitable for a log message or an audit
	 * attribute.
	 * @return the violations joined by {@code "; "}
	 */
	public String describe() {
		return String.join("; ", this.errors);
	}

}
