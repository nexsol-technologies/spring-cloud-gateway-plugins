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

package ch.nexsol.gateway.database.service;

import java.util.Map;

import org.apache.commons.text.StringEscapeUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Converts route element arguments between their in-memory map form and the JSON string
 * stored in the database.
 * <p>
 * Written as plain JSON. It used to be escaped a second time on the way in and unescaped
 * on the way out, which bought nothing &mdash; the value is already a JSON document, and
 * a column holds it fine &mdash; while turning every backslash of an argument into a
 * round-trip through two escaping rules. A row written by the previous versions is still
 * read: it is not a JSON document until it is unescaped, so it is told apart by failing
 * to parse. Unescaping is never applied to plain JSON, which it would corrupt &mdash; it
 * strips the backslash JSON itself needs in front of a backslash or a double quote.
 */
public class ArgumentService {

	private final ObjectMapper objectMapper;

	/**
	 * Creates the service with the JSON object mapper to use.
	 * @param objectMapper the object mapper used for (de)serialization
	 */
	public ArgumentService(ObjectMapper objectMapper) {
		super();
		this.objectMapper = objectMapper;
	}

	/**
	 * Deserializes a stored JSON string into an argument map.
	 * @param args the JSON representation of the arguments, plain or escaped
	 * @return the arguments as a map
	 */
	@SuppressWarnings("unchecked")
	public Map<String, String> jsonStringArgumentsToMap(String args) {
		try {
			return this.objectMapper.readValue(args, Map.class);
		}
		catch (JacksonException ex) {
			return this.objectMapper.readValue(StringEscapeUtils.unescapeJson(args), Map.class);
		}
	}

	/**
	 * Serializes an argument map into a JSON string.
	 * @param args the arguments as a map
	 * @return the JSON representation of the arguments
	 */
	public String mapArgumentsToJsonString(Map<String, String> args) {
		return this.objectMapper.writeValueAsString(args);
	}

}
