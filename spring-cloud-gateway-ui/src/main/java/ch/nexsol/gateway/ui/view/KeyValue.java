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

package ch.nexsol.gateway.ui.view;

import java.util.List;
import java.util.Map;

/**
 * One entry of a map, as a template iterates it.
 * <p>
 * A template reading {@code ${entry.key}} over a map resolves the accessor on the JDK's
 * entry class &mdash; {@code LinkedHashMap$Entry}, {@code KeyValueHolder} &mdash; which
 * carries no reflection metadata in a native image. A record of this module does, so the
 * maps the console iterates are handed over as a list of these.
 *
 * @param key the entry key
 * @param value the entry value
 */
public record KeyValue(String key, String value) {

	/**
	 * Lists the entries of a map in iteration order.
	 * @param map the map to list
	 * @return one entry per mapping
	 */
	public static List<KeyValue> of(Map<String, String> map) {
		return map.entrySet().stream().map((entry) -> new KeyValue(entry.getKey(), entry.getValue())).toList();
	}

}
