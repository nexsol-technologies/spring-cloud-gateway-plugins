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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The levels an instance was found configured with, so a level changed from this console
 * can be put back to the one the application declared.
 * <p>
 * Actuator has no way of asking for that. Posting no level to
 * {@code /actuator/loggers/{name}} clears the logger rather than restoring it: one
 * declared {@code debug} in {@code application.yml} goes back to inheriting from its
 * parent, and the setting the application shipped with is gone until it restarts. So the
 * levels are read once per instance and kept.
 * <p>
 * Only the loggers carrying a level are kept &mdash; a few dozen against the thousand or
 * so a context declares &mdash; because a logger with none is restored by clearing it,
 * which needs nothing remembered.
 * <p>
 * Two things this cannot claim. The baseline is what the <em>first read by this
 * console</em> saw, not what the instance started with: a level changed before that read
 * is the level that gets restored. And it lives in memory, so a console restart takes it
 * with it. Both are the trade for asking the running instance rather than asking it to
 * remember.
 */
public class LoggerBaseline {

	private final Map<String, Map<String, String>> byInstance = new ConcurrentHashMap<>();

	/**
	 * Returns the levels this instance was first seen with, recording them if this is the
	 * first read.
	 * @param instanceId the instance the payload was read from
	 * @param payload the answer of the loggers endpoint
	 * @return the configured levels, keyed by logger name; empty when none was set
	 */
	@SuppressWarnings("unchecked")
	public Map<String, String> of(String instanceId, Map<String, Object> payload) {
		return this.byInstance.computeIfAbsent(instanceId, (id) -> {
			Object loggers = payload.get("loggers");
			if (!(loggers instanceof Map<?, ?> declared)) {
				return Map.of();
			}
			Map<String, String> levels = new LinkedHashMap<>();
			((Map<String, Object>) declared).forEach((name, value) -> {
				if (value instanceof Map<?, ?> logger) {
					Object level = logger.get("configuredLevel");
					if (level != null) {
						levels.put(name, String.valueOf(level));
					}
				}
			});
			return Collections.unmodifiableMap(levels);
		});
	}

}
