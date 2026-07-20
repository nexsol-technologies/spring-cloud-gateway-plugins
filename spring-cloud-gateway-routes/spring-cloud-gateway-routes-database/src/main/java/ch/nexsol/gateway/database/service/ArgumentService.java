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
import tools.jackson.databind.ObjectMapper;

/**
 * Converts route element arguments between their in-memory map form and the escaped JSON
 * string stored in the database.
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
	 * Deserializes an escaped JSON string into an argument map.
	 * @param args the escaped JSON representation of the arguments
	 * @return the arguments as a map
	 */
	@SuppressWarnings("unchecked")
	public Map<String, String> jsonStringArgumentsToMap(String args) {
		return this.objectMapper.readValue(StringEscapeUtils.unescapeJson(args), Map.class);
	}

	/**
	 * Serializes an argument map into an escaped JSON string.
	 * @param args the arguments as a map
	 * @return the escaped JSON representation of the arguments
	 */
	public String mapArgumentsToJsonString(Map<String, String> args) {
		return StringEscapeUtils.escapeJson(this.objectMapper.writeValueAsString(args));
	}

}
