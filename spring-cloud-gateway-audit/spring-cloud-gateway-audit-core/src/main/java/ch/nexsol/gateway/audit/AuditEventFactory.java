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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import static ch.nexsol.gateway.audit.AuditAttributes.JWT_CLIENT_ID;
import static ch.nexsol.gateway.audit.AuditAttributes.JWT_IMPERSONATOR_USER_ID;
import static ch.nexsol.gateway.audit.AuditAttributes.JWT_IMPERSONATOR_USER_NAME;
import static ch.nexsol.gateway.audit.AuditAttributes.JWT_ISSUER_ID;
import static ch.nexsol.gateway.audit.AuditAttributes.JWT_USER_ID;
import static ch.nexsol.gateway.audit.AuditAttributes.METADATA_PREFIX;
import static ch.nexsol.gateway.audit.AuditAttributes.NONE_VALUE;
import static ch.nexsol.gateway.audit.AuditAttributes.REQUEST_HEADER_ACCEPT;
import static ch.nexsol.gateway.audit.AuditAttributes.REQUEST_HEADER_CONTENT_LENGTH;
import static ch.nexsol.gateway.audit.AuditAttributes.REQUEST_HEADER_CONTENT_TYPE;
import static ch.nexsol.gateway.audit.AuditAttributes.REQUEST_IP;
import static ch.nexsol.gateway.audit.AuditAttributes.REQUEST_METHOD;
import static ch.nexsol.gateway.audit.AuditAttributes.REQUEST_PARAMETERS;
import static ch.nexsol.gateway.audit.AuditAttributes.REQUEST_PATH;
import static ch.nexsol.gateway.audit.AuditAttributes.RESPONSE_HEADER_CONTENT_LENGTH;
import static ch.nexsol.gateway.audit.AuditAttributes.RESPONSE_HEADER_CONTENT_TYPE;
import static ch.nexsol.gateway.audit.AuditAttributes.RESPONSE_STATUS;
import static ch.nexsol.gateway.audit.AuditAttributes.ROUTE_ID;
import static ch.nexsol.gateway.audit.AuditAttributes.ROUTE_METADATA_PREFIX;
import static ch.nexsol.gateway.audit.AuditAttributes.SPAN_ID;
import static ch.nexsol.gateway.audit.AuditAttributes.TRACE_ID;
import static ch.nexsol.gateway.audit.AuditAttributes.UNKNOWN_VALUE;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Builds an {@link AuditEvent} from a {@link ServerWebExchange}, collecting only the
 * logical groups enabled through {@link AuditProperties.Groups}, plus the metadata
 * declared once for the whole gateway through {@link AuditProperties#getMetadata()}.
 */
public class AuditEventFactory {

	private static final String OBSERVATION_CONTEXT_ATTRIBUTE = "org.springframework.http.server.reactive.observation.ServerRequestObservationContext";

	private static final String BASIC_PREFIX = "basic ";

	private final AuditProperties.Groups groups;

	private final Map<String, String> metadata;

	/**
	 * Create a new factory.
	 * @param properties the audit properties holding the group feature flags and the
	 * metadata stamped on every event
	 */
	public AuditEventFactory(AuditProperties properties) {
		this.groups = properties.getGroups();
		this.metadata = new LinkedHashMap<>(properties.getMetadata());
	}

	/**
	 * Build the audit event for the given exchange. The response and JWT groups are read
	 * lazily so the exchange should be fully processed before this is called.
	 * @param exchange the current exchange
	 * @return a mono emitting the audit event
	 */
	public Mono<AuditEvent> create(ServerWebExchange exchange) {
		Map<String, String> attributes = new LinkedHashMap<>();
		if (this.groups.isRequest()) {
			collectRequest(exchange.getRequest(), attributes);
		}
		if (this.groups.isResponse()) {
			collectResponse(exchange.getResponse(), attributes);
		}
		if (this.groups.isTrace()) {
			collectTrace(exchange, attributes);
		}
		if (this.groups.isRoute()) {
			collectRoute(exchange, attributes);
		}
		collectMetadata(attributes);
		if (!this.groups.isJwt()) {
			return Mono.just(new AuditEvent(Instant.now(), attributes));
		}
		return exchange.getPrincipal()
			.map(Optional::<Principal>of)
			.defaultIfEmpty(Optional.empty())
			.map((principal) -> {
				collectJwt(exchange, principal.orElse(null), attributes);
				return new AuditEvent(Instant.now(), attributes);
			});
	}

	private void collectRequest(ServerHttpRequest request, Map<String, String> attributes) {
		HttpHeaders headers = request.getHeaders();
		attributes.put(REQUEST_HEADER_ACCEPT, headerOrNone(headers, HttpHeaders.ACCEPT));
		attributes.put(REQUEST_HEADER_CONTENT_LENGTH, String.valueOf(headers.getContentLength()));
		attributes.put(REQUEST_HEADER_CONTENT_TYPE, contentTypeOrUnknown(headers));
		attributes.put(REQUEST_IP, remoteIp(request));
		attributes.put(REQUEST_METHOD, request.getMethod().name());
		attributes.put(REQUEST_PARAMETERS, parametersOrNone(request));
		attributes.put(REQUEST_PATH, request.getPath().value());
	}

	private void collectResponse(ServerHttpResponse response, Map<String, String> attributes) {
		HttpHeaders headers = response.getHeaders();
		attributes.put(RESPONSE_HEADER_CONTENT_LENGTH, String.valueOf(headers.getContentLength()));
		attributes.put(RESPONSE_HEADER_CONTENT_TYPE, contentTypeOrUnknown(headers));
		attributes.put(RESPONSE_STATUS, statusValue(response.getStatusCode()));
	}

	private void collectTrace(ServerWebExchange exchange, Map<String, String> attributes) {
		attributes.put(TRACE_ID, NONE_VALUE);
		attributes.put(SPAN_ID, NONE_VALUE);
		Object context = exchange.getAttribute(OBSERVATION_CONTEXT_ATTRIBUTE);
		if (context instanceof ServerRequestObservationContext observationContext) {
			TracingContext tracingContext = observationContext.get(TracingContext.class);
			if (tracingContext != null) {
				Span span = tracingContext.getSpan();
				if (span != null && span.context() != null) {
					attributes.put(TRACE_ID, span.context().traceId());
					attributes.put(SPAN_ID, span.context().spanId());
				}
			}
		}
	}

	private void collectRoute(ServerWebExchange exchange, Map<String, String> attributes) {
		Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
		if (route == null) {
			// No route handled the exchange: the global web filter also audits what the
			// gateway served itself, and a request that matched nothing.
			attributes.put(ROUTE_ID, NONE_VALUE);
			return;
		}
		attributes.put(ROUTE_ID, StringUtils.hasText(route.getId()) ? route.getId() : NONE_VALUE);
		route.getMetadata().forEach((key, value) -> attributes.put(ROUTE_METADATA_PREFIX + key, stringValue(value)));
	}

	private void collectMetadata(Map<String, String> attributes) {
		this.metadata.forEach((key, value) -> attributes.put(METADATA_PREFIX + key, stringValue(value)));
	}

	private void collectJwt(ServerWebExchange exchange, Principal principal, Map<String, String> attributes) {
		attributes.put(JWT_CLIENT_ID, NONE_VALUE);
		attributes.put(JWT_IMPERSONATOR_USER_ID, NONE_VALUE);
		attributes.put(JWT_IMPERSONATOR_USER_NAME, NONE_VALUE);
		attributes.put(JWT_ISSUER_ID, NONE_VALUE);
		attributes.put(JWT_USER_ID, NONE_VALUE);

		Jwt jwt = extractJwt(principal);
		if (jwt != null) {
			collectFromJwt(jwt, attributes);
			return;
		}
		String basicUser = basicAuthUser(exchange.getRequest());
		if (basicUser != null) {
			attributes.put(JWT_USER_ID, basicUser);
		}
		else if (principal != null && StringUtils.hasText(principal.getName())) {
			attributes.put(JWT_USER_ID, principal.getName());
		}
	}

	private void collectFromJwt(Jwt jwt, Map<String, String> attributes) {
		attributes.put(JWT_USER_ID, firstNonBlank(jwt.getClaimAsString("preferred_username"), jwt.getSubject()));
		attributes.put(JWT_CLIENT_ID, firstNonBlank(jwt.getClaimAsString("azp"), jwt.getClaimAsString("client_id")));
		attributes.put(JWT_ISSUER_ID, (jwt.getIssuer() != null) ? jwt.getIssuer().toString() : NONE_VALUE);

		// RFC 8693 "act" (actor) claim carries the impersonator when a token is
		// exchanged.
		Map<String, Object> actor = jwt.getClaimAsMap("act");
		if (actor != null) {
			Object impersonatorId = actor.get("sub");
			Object impersonatorName = actor.containsKey("preferred_username") ? actor.get("preferred_username")
					: actor.get("name");
			if (impersonatorId != null) {
				attributes.put(JWT_IMPERSONATOR_USER_ID, impersonatorId.toString());
			}
			if (impersonatorName != null) {
				attributes.put(JWT_IMPERSONATOR_USER_NAME, impersonatorName.toString());
			}
		}
	}

	private Jwt extractJwt(Principal principal) {
		if (principal instanceof AbstractOAuth2TokenAuthenticationToken<?> token
				&& token.getToken() instanceof Jwt jwt) {
			return jwt;
		}
		if (principal instanceof Authentication authentication && authentication.getPrincipal() instanceof Jwt jwt) {
			return jwt;
		}
		return null;
	}

	private String basicAuthUser(ServerHttpRequest request) {
		String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.toLowerCase().startsWith(BASIC_PREFIX)) {
			return null;
		}
		try {
			String decoded = new String(Base64.getDecoder().decode(header.substring(BASIC_PREFIX.length())),
					StandardCharsets.UTF_8);
			int separator = decoded.indexOf(':');
			String user = (separator >= 0) ? decoded.substring(0, separator) : decoded;
			return StringUtils.hasText(user) ? user : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private String headerOrNone(HttpHeaders headers, String name) {
		List<String> values = headers.get(name);
		if (values == null || values.isEmpty()) {
			return NONE_VALUE;
		}
		return String.join(",", values);
	}

	private String contentTypeOrUnknown(HttpHeaders headers) {
		MediaType contentType = headers.getContentType();
		return (contentType != null) ? contentType.toString() : UNKNOWN_VALUE;
	}

	private String remoteIp(ServerHttpRequest request) {
		String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
		if (StringUtils.hasText(forwarded)) {
			return forwarded.split(",")[0].trim();
		}
		InetSocketAddress remoteAddress = request.getRemoteAddress();
		if (remoteAddress != null && remoteAddress.getAddress() != null) {
			return remoteAddress.getAddress().getHostAddress();
		}
		return NONE_VALUE;
	}

	private String parametersOrNone(ServerHttpRequest request) {
		String query = request.getURI().getRawQuery();
		return StringUtils.hasText(query) ? query : NONE_VALUE;
	}

	private String statusValue(HttpStatusCode status) {
		if (status == null) {
			return NONE_VALUE;
		}
		if (status instanceof HttpStatus httpStatus) {
			return httpStatus.name();
		}
		return String.valueOf(status.value());
	}

	private static String stringValue(Object value) {
		return (value != null) ? value.toString() : NONE_VALUE;
	}

	private static String firstNonBlank(String first, String second) {
		if (StringUtils.hasText(first)) {
			return first;
		}
		if (StringUtils.hasText(second)) {
			return second;
		}
		return NONE_VALUE;
	}

}
