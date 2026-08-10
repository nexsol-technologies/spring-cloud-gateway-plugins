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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

/**
 * Validates a message body against the {@code content} of a contract, shared by both
 * directions: a request body and a response body are checked exactly the same way, only
 * the wording of the violations differs.
 * <p>
 * Only JSON bodies are validated against a schema. Any other media type is checked for
 * being declared at all and then forwarded, since the contract carries no
 * machine-readable schema this filter could apply to it.
 */
class OpenapiBodyValidator {

	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Validates a body against the declared content of an operation.
	 * @param contract the contract the schemas are compiled from
	 * @param cacheKey the key the compiled schema is kept under
	 * @param content the content the contract declares, may be {@code null}
	 * @param contentType the media type of the message, may be {@code null}
	 * @param body the raw body
	 * @param role how the body is named in a violation, {@code request} or
	 * {@code response}
	 * @return the violations found
	 */
	ValidationReport validate(OpenapiContract contract, String cacheKey, Content content, MediaType contentType,
			byte[] body, String role) {
		if (content == null || content.isEmpty()) {
			// The contract declares no body for this operation: there is nothing to hold
			// the payload against, and an undeclared body is not in itself a violation.
			return ValidationReport.empty();
		}
		if (contentType == null) {
			return ValidationReport.of(role + " body is present but carries no Content-Type");
		}
		DeclaredBody declared = match(content, contentType);
		if (declared == null) {
			return ValidationReport.of(role + " Content-Type '" + contentType
					+ "' is not declared by the contract, which declares " + content.keySet());
		}
		Schema<?> schema = declared.schema();
		if (schema == null || !ValidationMediaTypes.isJson(contentType)) {
			return ValidationReport.empty();
		}
		JsonNode instance;
		try {
			instance = this.mapper.readTree(body);
		}
		catch (IOException ex) {
			return ValidationReport.of(role + " body is not valid JSON: " + ex.getMessage());
		}
		if (instance == null) {
			return ValidationReport.of(role + " body is empty but a " + contentType + " payload is declared");
		}
		Set<ValidationMessage> messages = contract.compiledSchema(cacheKey, schema).validate(instance);
		if (messages.isEmpty()) {
			return ValidationReport.empty();
		}
		List<String> errors = new ArrayList<>(messages.size());
		for (ValidationMessage message : messages) {
			errors.add(role + " body " + describe(message));
		}
		return new ValidationReport(errors);
	}

	/**
	 * Returns the content entry covering the given media type, or {@code null} when the
	 * contract declares none. An exact type and subtype match wins over a merely
	 * compatible one, so a catch-all declaration never shadows a precise one.
	 */
	private static DeclaredBody match(Content content, MediaType contentType) {
		DeclaredBody compatible = null;
		for (var entry : content.entrySet()) {
			MediaType declared = parse(entry.getKey());
			if (declared == null) {
				continue;
			}
			if (declared.equalsTypeAndSubtype(contentType)) {
				return new DeclaredBody(entry.getValue().getSchema());
			}
			if (compatible == null && declared.isCompatibleWith(contentType)) {
				compatible = new DeclaredBody(entry.getValue().getSchema());
			}
		}
		return compatible;
	}

	private static MediaType parse(String mediaType) {
		if (!StringUtils.hasText(mediaType)) {
			return null;
		}
		try {
			return MediaType.parseMediaType(mediaType);
		}
		catch (InvalidMediaTypeException ex) {
			return null;
		}
	}

	private static String describe(ValidationMessage message) {
		String location = message.getInstanceLocation().toString();
		return StringUtils.hasText(location) ? "at '" + location + "' " + message.getMessage() : message.getMessage();
	}

	/**
	 * The body the contract declares for a media type. A {@code null} schema means the
	 * media type is declared but carries nothing to validate against.
	 *
	 * @param schema the declared schema, may be {@code null}
	 */
	private record DeclaredBody(Schema<?> schema) {

	}

}
