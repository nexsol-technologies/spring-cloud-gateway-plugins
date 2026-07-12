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

package ch.nexsol.gateway.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Serializes an {@link AuditEvent}'s attributes to a JSON string. Shared by the provider
 * modules so they render the audit payload consistently.
 */
public class AuditEventSerializer {

	private final ObjectMapper objectMapper;

	/**
	 * Create a new serializer.
	 * @param objectMapper the object mapper used to render the attributes
	 */
	public AuditEventSerializer(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Render the event attributes as a JSON object.
	 * @param event the event to serialize
	 * @return the JSON representation of the attributes
	 */
	public String toJson(AuditEvent event) {
		try {
			return this.objectMapper.writeValueAsString(event.attributes());
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to serialize audit event", ex);
		}
	}

}
