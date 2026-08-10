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
import java.util.Map;

import ch.nexsol.gateway.validation.OpenapiContract.Resolution;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import org.springframework.http.HttpHeaders;
import org.springframework.util.CollectionUtils;

/**
 * Validates a response against the operation the contract resolved for the request: that
 * the status is one the contract declares, that the headers it requires are there, and
 * that the body honours the declared schema.
 */
public class OpenapiResponseValidator {

	private final OpenapiBodyValidator bodyValidator = new OpenapiBodyValidator();

	/**
	 * Validates a response against the contract.
	 * @param contract the contract the schemas are compiled from
	 * @param operationKey the operation, as {@code METHOD /template}, used to key the
	 * compiled schemas
	 * @param found the operation the request was resolved to
	 * @param statusCode the status the downstream service answered with
	 * @param headers the response headers
	 * @param body the raw response body, empty when the response carries none, and
	 * {@code null} when it was not read
	 * @return the violations found
	 */
	public ValidationReport validate(OpenapiContract contract, String operationKey, Resolution.Found found,
			int statusCode, HttpHeaders headers, byte[] body) {
		ApiResponses responses = found.operation().getResponses();
		if (CollectionUtils.isEmpty(responses)) {
			// An operation declaring no response at all constrains nothing.
			return ValidationReport.empty();
		}
		Declared declared = declared(responses, statusCode);
		if (declared == null) {
			return ValidationReport.of("response status " + statusCode
					+ " is not declared by the contract, which declares " + responses.keySet());
		}
		ValidationReport report = validateHeaders(declared.response(), headers);
		if (body == null) {
			return report;
		}
		if (body.length == 0) {
			return report;
		}
		return report.and(this.bodyValidator.validate(contract, operationKey + "#response:" + declared.key(),
				declared.response().getContent(), headers.getContentType(), body, "response"));
	}

	private static ValidationReport validateHeaders(ApiResponse response, HttpHeaders headers) {
		Map<String, Header> declared = response.getHeaders();
		if (CollectionUtils.isEmpty(declared)) {
			return ValidationReport.empty();
		}
		List<String> errors = new ArrayList<>();
		declared.forEach((name, header) -> {
			if (Boolean.TRUE.equals(header.getRequired()) && !headers.containsHeader(name)) {
				errors.add("response header '" + name + "' is required by the contract but is missing");
			}
		});
		return new ValidationReport(errors);
	}

	/**
	 * Returns the response the contract declares for a status, trying the exact code
	 * first, then the range such as {@code 2XX}, then {@code default}, as the
	 * specification prescribes.
	 */
	private static Declared declared(ApiResponses responses, int statusCode) {
		String exact = String.valueOf(statusCode);
		ApiResponse response = responses.get(exact);
		if (response != null) {
			return new Declared(exact, response);
		}
		String range = (statusCode / 100) + "XX";
		response = responses.get(range);
		if (response != null) {
			return new Declared(range, response);
		}
		response = responses.get("default");
		return (response != null) ? new Declared("default", response) : null;
	}

	/**
	 * The response the contract declares for a status, together with the key it is
	 * declared under so the compiled schema can be keyed on it.
	 *
	 * @param key the declaring key, an exact status, a range or {@code default}
	 * @param response the declared response
	 */
	private record Declared(String key, ApiResponse response) {

	}

}
