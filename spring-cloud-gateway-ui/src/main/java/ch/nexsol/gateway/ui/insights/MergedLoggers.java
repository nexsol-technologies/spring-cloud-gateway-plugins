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

package ch.nexsol.gateway.ui.insights;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The loggers of several instances, read as one.
 * <p>
 * A logging level is a property of the deployment rather than of one JVM: raising a level
 * on the instance that happened to answer the console leaves the other instances
 * recording nothing, and the request being chased lands on one of those. So the fleet is
 * read together and written together, and a single instance is what a reader asks for
 * explicitly.
 * <p>
 * Where the instances agree, the level they agree on is the one reported. Where they do
 * not &mdash; one was set apart, or one restarted and lost a change &mdash; the logger is
 * marked {@code mixed} and no level is claimed. Reporting the first instance's answer for
 * the whole fleet is the one thing this must not do: it would show a level that most of
 * the fleet is not recording at.
 */
final class MergedLoggers {

	private MergedLoggers() {
	}

	/**
	 * Merges what each instance answered into one payload of the same shape.
	 * @param answers the payloads, keyed by the instance they came from, in the order the
	 * instances were listed
	 * @return the merged payload, carrying which instances answered and which loggers
	 * they disagree on
	 */
	static Map<String, Object> of(Map<String, Map<String, Object>> answers) {
		Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
		Set<String> mixed = new LinkedHashSet<>();
		for (Map<String, Object> answer : answers.values()) {
			eachLogger(answer, (name, logger) -> {
				Map<String, Object> known = merged.get(name);
				if (known == null) {
					merged.put(name, new LinkedHashMap<>(logger));
					return;
				}
				reconcile(name, known, logger, mixed);
			});
		}
		// A logger only some instances declare is one they do not agree on either: the
		// others are recording at whatever their own hierarchy says.
		if (answers.size() > 1) {
			answers.forEach((instance, answer) -> merged.forEach((name, logger) -> {
				if (!declares(answer, name)) {
					mixed.add(name);
				}
			}));
		}
		for (String name : mixed) {
			Map<String, Object> logger = merged.get(name);
			logger.put("configuredLevel", null);
			logger.put("mixed", true);
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("loggers", merged);
		payload.put("levels", levels(answers));
		payload.put("_merged", List.copyOf(answers.keySet()));
		return payload;
	}

	/**
	 * Two answers for the same logger. They agree or they do not; the level is only kept
	 * while they do.
	 */
	private static void reconcile(String name, Map<String, Object> known, Map<String, Object> other,
			Set<String> mixed) {
		if (!same(known.get("configuredLevel"), other.get("configuredLevel"))) {
			mixed.add(name);
		}
		if (!same(known.get("effectiveLevel"), other.get("effectiveLevel"))) {
			known.put("effectiveLevel", null);
			mixed.add(name);
		}
	}

	private static boolean same(Object left, Object right) {
		return (left != null) ? left.equals(right) : (right == null);
	}

	@SuppressWarnings("unchecked")
	private static boolean declares(Map<String, Object> answer, String name) {
		return (answer.get("loggers") instanceof Map<?, ?> loggers)
				&& ((Map<String, Object>) loggers).containsKey(name);
	}

	@SuppressWarnings("unchecked")
	private static void eachLogger(Map<String, Object> answer, LoggerConsumer consumer) {
		if (!(answer.get("loggers") instanceof Map<?, ?> loggers)) {
			return;
		}
		((Map<String, Object>) loggers).forEach((name, value) -> {
			if (value instanceof Map<?, ?> logger) {
				consumer.accept(name, (Map<String, Object>) logger);
			}
		});
	}

	/**
	 * The levels Actuator lists, taken from the first answer that carries them: they are
	 * the same everywhere, being what the logging system supports rather than what any
	 * instance was configured with.
	 */
	private static List<Object> levels(Map<String, Map<String, Object>> answers) {
		for (Map<String, Object> answer : answers.values()) {
			if (answer.get("levels") instanceof List<?> levels) {
				return new ArrayList<>(levels);
			}
		}
		return List.of();
	}

	@FunctionalInterface
	private interface LoggerConsumer {

		void accept(String name, Map<String, Object> logger);

	}

}
