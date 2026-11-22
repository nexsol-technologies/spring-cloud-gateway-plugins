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
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import com.networknt.schema.Error;

/**
 * The violations one message was found to commit against one schema, the first
 * {@value #LIMIT} of them.
 * <p>
 * The validator collects every violation and offers no way to ask for fewer: a body that
 * breaks its schema on each element of a large array yields as many of them as it has
 * elements, which the gateway then copies into the answer, the log line and the audit
 * event. This list is the one the validator is given to collect into, and it ends the
 * validation by throwing once it is full &mdash; the verdict is settled by the first
 * violation, the others only say how.
 * <p>
 * It bounds what a schema collects directly. The branches of an {@code anyOf} or a
 * {@code oneOf} are evaluated into lists of the validator's own, since a branch that
 * fails proves nothing until the others did too.
 */
final class Violations extends ArrayList<Error> {

	static final int LIMIT = 20;

	private static final long serialVersionUID = 1L;

	private boolean truncated;

	@Override
	public boolean add(Error violation) {
		if (size() >= LIMIT) {
			this.truncated = true;
			throw LimitReached.INSTANCE;
		}
		return super.add(violation);
	}

	@Override
	public boolean addAll(Collection<? extends Error> violations) {
		violations.forEach(this::add);
		return !violations.isEmpty();
	}

	/**
	 * Renders violations for a report, saying so when there were more than are listed.
	 * @param violations the violations a validation returned
	 * @param text how one violation reads in the report
	 * @return the lines of the report
	 */
	static List<String> describe(List<Error> violations, Function<Error, String> text) {
		List<String> lines = new ArrayList<>(violations.size() + 1);
		for (Error violation : violations) {
			lines.add(text.apply(violation));
		}
		if (violations instanceof Violations bounded && bounded.truncated) {
			lines.add("only the first " + LIMIT + " violations are listed");
		}
		return lines;
	}

	/**
	 * Thrown to end a validation that already found as many violations as are kept.
	 */
	static final class LimitReached extends RuntimeException {

		static final LimitReached INSTANCE = new LimitReached();

		private static final long serialVersionUID = 1L;

		private LimitReached() {
			super(null, null, false, false);
		}

	}

}
