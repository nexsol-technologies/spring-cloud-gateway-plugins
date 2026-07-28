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

/**
 * Attribute keys used in an {@link AuditEvent} together with the placeholder values
 * emitted when an attribute is absent.
 */
public final class AuditAttributes {

	/**
	 * Value used when an attribute has no meaningful value.
	 */
	public static final String NONE_VALUE = "_none_";

	/**
	 * Value used when an attribute is expected but could not be resolved.
	 */
	public static final String UNKNOWN_VALUE = "unknown";

	/**
	 * Client id carried by the JWT.
	 */
	public static final String JWT_CLIENT_ID = "jwt.client.id";

	/**
	 * User id of the impersonator, if the request is impersonated.
	 */
	public static final String JWT_IMPERSONATOR_USER_ID = "jwt.impersonator.user.id";

	/**
	 * User name of the impersonator, if the request is impersonated.
	 */
	public static final String JWT_IMPERSONATOR_USER_NAME = "jwt.impersonator.user.name";

	/**
	 * Issuer of the JWT.
	 */
	public static final String JWT_ISSUER_ID = "jwt.issuer.id";

	/**
	 * Authenticated user id (JWT subject or Basic-auth user name).
	 */
	public static final String JWT_USER_ID = "jwt.user.id";

	/**
	 * Id of the gateway route that handled the exchange.
	 */
	public static final String ROUTE_ID = "route.id";

	/**
	 * Prefix of the attributes carrying the metadata of that route: a route metadata
	 * named {@code tenant} is audited as {@code route.metadata.tenant}.
	 */
	public static final String ROUTE_METADATA_PREFIX = "route.metadata.";

	/**
	 * Prefix of the attributes carrying the metadata configured globally: a metadata
	 * named {@code environment} is audited as {@code metadata.environment}.
	 */
	public static final String METADATA_PREFIX = "metadata.";

	/**
	 * Request {@code Accept} header.
	 */
	public static final String REQUEST_HEADER_ACCEPT = "request.header.accept";

	/**
	 * Request {@code Content-Length} header.
	 */
	public static final String REQUEST_HEADER_CONTENT_LENGTH = "request.header.content-length";

	/**
	 * Request {@code Content-Type} header.
	 */
	public static final String REQUEST_HEADER_CONTENT_TYPE = "request.header.content-type";

	/**
	 * Remote client ip.
	 */
	public static final String REQUEST_IP = "request.ip";

	/**
	 * Request HTTP method.
	 */
	public static final String REQUEST_METHOD = "request.method";

	/**
	 * Request query parameters.
	 */
	public static final String REQUEST_PARAMETERS = "request.parameters";

	/**
	 * Request path.
	 */
	public static final String REQUEST_PATH = "request.path";

	/**
	 * Response {@code Content-Length} header.
	 */
	public static final String RESPONSE_HEADER_CONTENT_LENGTH = "response.header.content-length";

	/**
	 * Response {@code Content-Type} header.
	 */
	public static final String RESPONSE_HEADER_CONTENT_TYPE = "response.header.content-type";

	/**
	 * Response HTTP status.
	 */
	public static final String RESPONSE_STATUS = "response.status";

	/**
	 * Current trace id.
	 */
	public static final String TRACE_ID = "trace.id";

	/**
	 * Current span id.
	 */
	public static final String SPAN_ID = "span.id";

	private AuditAttributes() {

	}

}
