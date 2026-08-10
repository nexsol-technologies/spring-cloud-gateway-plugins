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

/**
 * The exchange attribute the validation outcome is published under, and the names it is
 * audited with.
 * <p>
 * The filter never talks to the auditing plugin: it only stamps the outcome on the
 * exchange, and the {@code validation} audit group picks it up when both plugins are
 * present. That keeps this module free of any dependency towards the audit one, at the
 * cost of one string constant declared on both sides &mdash; the value of
 * {@link #VALIDATION_ATTRIBUTES_ATTR} is what the two agree on and must not change
 * without changing it in {@code ch.nexsol.gateway.audit.AuditEventFactory} too.
 */
public final class ValidationAttributes {

	/**
	 * Exchange attribute holding the validation outcome as a {@code Map<String, String>}
	 * ready to be audited. Read by the {@code validation} audit group.
	 */
	public static final String VALIDATION_ATTRIBUTES_ATTR = "gatewayOpenapiValidationAttributes";

	/**
	 * Whether the request honoured the contract.
	 */
	public static final String REQUEST_VALID = "openapi.validation.request.valid";

	/**
	 * The ways the request broke the contract, joined by {@code "; "}.
	 */
	public static final String REQUEST_ERRORS = "openapi.validation.request.errors";

	/**
	 * Whether the response honoured the contract.
	 */
	public static final String RESPONSE_VALID = "openapi.validation.response.valid";

	/**
	 * The ways the response broke the contract, joined by {@code "; "}.
	 */
	public static final String RESPONSE_ERRORS = "openapi.validation.response.errors";

	/**
	 * The contract operation the exchange was validated against, as {@code METHOD
	 * /path/{template}}.
	 */
	public static final String OPERATION = "openapi.validation.operation";

	private ValidationAttributes() {

	}

}
