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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import ch.nexsol.gateway.validation.OpenapiContract.Resolution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.networknt.schema.ValidationMessage;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;

import org.springframework.http.HttpCookie;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.CollectionUtils;

/**
 * Validates a request against the operation the contract resolved for it: its parameters
 * first, then its body.
 */
public class OpenapiRequestValidator {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final OpenapiBodyValidator bodyValidator = new OpenapiBodyValidator();

	/**
	 * Validates the path, query, header and cookie parameters the contract declares for
	 * the operation.
	 * @param contract the contract the schemas are compiled from
	 * @param operationKey the operation, as {@code METHOD /template}, used to key the
	 * compiled schemas
	 * @param found the resolved operation
	 * @param request the request being validated
	 * @return the violations found
	 */
	public ValidationReport validateParameters(OpenapiContract contract, String operationKey, Resolution.Found found,
			ServerHttpRequest request) {
		ValidationReport report = ValidationReport.empty();
		for (Parameter parameter : found.parameters()) {
			report = report.and(validateParameter(contract, operationKey, found, parameter, request));
		}
		return report;
	}

	/**
	 * Validates the request body against the schema the contract declares for the
	 * operation.
	 * @param contract the contract the schemas are compiled from
	 * @param operationKey the operation, as {@code METHOD /template}, used to key the
	 * compiled schemas
	 * @param found the resolved operation
	 * @param contentType the request media type, may be {@code null}
	 * @param body the raw request body, empty when the request carries none
	 * @return the violations found
	 */
	public ValidationReport validateBody(OpenapiContract contract, String operationKey, Resolution.Found found,
			MediaType contentType, byte[] body) {
		RequestBody requestBody = found.operation().getRequestBody();
		if (requestBody == null) {
			return ValidationReport.empty();
		}
		if (body.length == 0) {
			return Boolean.TRUE.equals(requestBody.getRequired())
					? ValidationReport.of("request body is required by the contract but is missing")
					: ValidationReport.empty();
		}
		return this.bodyValidator.validate(contract, operationKey + "#request", requestBody.getContent(), contentType,
				body, "request");
	}

	private ValidationReport validateParameter(OpenapiContract contract, String operationKey, Resolution.Found found,
			Parameter parameter, ServerHttpRequest request) {
		List<String> values = values(parameter, found, request);
		if (CollectionUtils.isEmpty(values)) {
			return Boolean.TRUE.equals(parameter.getRequired())
					? ValidationReport.of(describe(parameter) + " is required by the contract but is missing")
					: ValidationReport.empty();
		}
		Schema<?> schema = parameter.getSchema();
		if (schema == null) {
			return ValidationReport.empty();
		}
		JsonNode instance;
		try {
			instance = coerce(values, parameter, schema);
		}
		catch (NumberFormatException ex) {
			return ValidationReport
				.of(describe(parameter) + " is not a valid " + primaryType(schema) + ": '" + values.get(0) + "'");
		}
		String cacheKey = operationKey + "#param:" + parameter.getIn() + ":" + parameter.getName();
		Set<ValidationMessage> messages = contract.compiledSchema(cacheKey, schema).validate(instance);
		if (messages.isEmpty()) {
			return ValidationReport.empty();
		}
		List<String> errors = new ArrayList<>(messages.size());
		for (ValidationMessage message : messages) {
			errors.add(describe(parameter) + " " + message.getMessage());
		}
		return new ValidationReport(errors);
	}

	private static List<String> values(Parameter parameter, Resolution.Found found, ServerHttpRequest request) {
		String name = parameter.getName();
		String in = (parameter.getIn() != null) ? parameter.getIn() : "query";
		switch (in) {
			case "path" -> {
				String value = found.pathVariables().get(name);
				return (value != null) ? List.of(value) : List.of();
			}
			case "header" -> {
				return request.getHeaders().getOrEmpty(name);
			}
			case "cookie" -> {
				List<HttpCookie> cookies = request.getCookies().get(name);
				if (CollectionUtils.isEmpty(cookies)) {
					return List.of();
				}
				return cookies.stream().map(HttpCookie::getValue).toList();
			}
			default -> {
				return request.getQueryParams().getOrDefault(name, List.of());
			}
		}
	}

	/**
	 * Turns the raw textual values of a parameter into the JSON node its schema expects.
	 * Everything arrives as text over HTTP, so an {@code integer} parameter has to be
	 * read as a number before a schema can say anything useful about its bounds.
	 */
	private static JsonNode coerce(List<String> values, Parameter parameter, Schema<?> schema) {
		String type = primaryType(schema);
		if ("array".equals(type)) {
			Schema<?> items = schema.getItems();
			ArrayNode array = NODES.arrayNode();
			for (String element : elements(values, parameter)) {
				array.add(coerceScalar(element, (items != null) ? primaryType(items) : null));
			}
			return array;
		}
		return coerceScalar(values.get(0), type);
	}

	/**
	 * Returns the elements of an array parameter. Repeated parameters are the default
	 * form ({@code style: form, explode: true}); a single comma separated value is the
	 * non-exploded form.
	 */
	private static List<String> elements(List<String> values, Parameter parameter) {
		if (values.size() > 1 || !Boolean.FALSE.equals(parameter.getExplode())) {
			return values;
		}
		return Arrays.asList(values.get(0).split(",", -1));
	}

	private static JsonNode coerceScalar(String value, String type) {
		if (type == null) {
			return NODES.textNode(value);
		}
		return switch (type) {
			case "integer" -> NODES.numberNode(Long.parseLong(value));
			case "number" -> NODES.numberNode(new BigDecimal(value));
			case "boolean" -> booleanNode(value);
			default -> NODES.textNode(value);
		};
	}

	private static JsonNode booleanNode(String value) {
		if ("true".equalsIgnoreCase(value)) {
			return NODES.booleanNode(true);
		}
		if ("false".equalsIgnoreCase(value)) {
			return NODES.booleanNode(false);
		}
		// Reported the same way as a malformed number, by the caller.
		throw new NumberFormatException(value);
	}

	/**
	 * Returns the type of a schema, reading both the 3.1 {@code types} set and the 3.0
	 * {@code type} field. A nullable 3.1 schema declares two types; the one that is not
	 * {@code null} is the one values have to be read as.
	 */
	private static String primaryType(Schema<?> schema) {
		Set<String> types = schema.getTypes();
		if (!CollectionUtils.isEmpty(types)) {
			return types.stream().filter((type) -> !"null".equals(type)).findFirst().orElse(null);
		}
		return schema.getType();
	}

	private static String describe(Parameter parameter) {
		return parameter.getIn() + " parameter '" + parameter.getName() + "'";
	}

}
