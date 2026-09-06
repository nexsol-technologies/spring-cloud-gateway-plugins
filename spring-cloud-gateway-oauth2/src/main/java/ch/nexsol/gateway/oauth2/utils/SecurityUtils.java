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

package ch.nexsol.gateway.oauth2.utils;

import java.util.List;
import java.util.Optional;

import ch.nexsol.gateway.oauth2.filter.webfilter.BasicAuthExchangeToAccessTokenGatewayWebFilter.BasicValue;
import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;
import org.springframework.util.StringUtils;

/**
 * Security-related helper methods shared across the OAuth 2.0 gateway plugins.
 */
public final class SecurityUtils {

	/**
	 * Lower-cased prefix of a Basic {@code Authorization} header value.
	 */
	public static final String HEADER_AUTHORIZATION_BASIC = "basic ";

	private static final String HTTP_SCHEME = "http";

	private static final String HTTPS_SCHEME = "https";

	private static final String ACTUATOR_PREFIX = "/actuator";

	private SecurityUtils() {

	}

	/**
	 * Whether a request is one the Basic-auth exchange handles at all, whatever
	 * credentials it carries.
	 * <p>
	 * The exchange filter and the security chain matcher MUST agree on this, and that is
	 * the only reason this lives here rather than inside the filter. The chain the plugin
	 * contributes declares no authorization rule &mdash; the exchange itself is what
	 * authorizes &mdash; so every request its matcher accepts and the filter then skips
	 * would be served with no check at all, having taken the request away from the chains
	 * of the application. Anything this method rejects must fall through to those chains
	 * as if the plugin were not there.
	 * @param request the request to test
	 * @return {@code true} when the exchange applies to this request
	 */
	public static boolean isCandidateForExchange(ServerHttpRequest request) {
		String scheme = request.getURI().getScheme();
		if (!HTTP_SCHEME.equalsIgnoreCase(scheme) && !HTTPS_SCHEME.equalsIgnoreCase(scheme)) {
			return false;
		}
		return !request.getPath().value().startsWith(ACTUATOR_PREFIX);
	}

	/**
	 * Extract the Basic credentials a request carries, from the {@code Authorization}
	 * header or, when the configuration enables it, from the credentials query parameter.
	 * <p>
	 * The header wins whenever it carries usable Basic credentials, so a client that can
	 * set one is never affected by the query parameter being enabled.
	 * <p>
	 * Everything read here is attacker-controlled and nothing throws: a value that is not
	 * valid Base64, or that carries no {@code :} separator, yields an empty result and
	 * the request is left alone. The exchange filter and the security chain matcher both
	 * go through this method, so they always agree on which requests belong to the
	 * exchange.
	 * @param request the request to read the credentials from
	 * @param properties the Basic-auth exchange configuration
	 * @return the decoded credentials, or empty when the request carries none
	 */
	public static Optional<BasicValue> resolveBasicValue(ServerHttpRequest request,
			BasicAuthExchangeToAccessTokenProperties properties) {
		Optional<BasicValue> fromHeader = fromAuthorizationHeader(request);
		if (fromHeader.isPresent() || !properties.isCredentialsInQueryParam()) {
			return fromHeader;
		}
		return fromQueryParam(request, properties.getCredentialsQueryParamName());
	}

	/**
	 * Build a matcher that matches an exchange whose Basic credentials name a client
	 * configured for token exchange.
	 * @param properties the Basic-auth exchange properties used to check the user
	 * @return a matcher accepting configured Basic-auth requests
	 */
	public static ServerWebExchangeMatcher basicCredentialsMatcher(
			BasicAuthExchangeToAccessTokenProperties properties) {
		return (exchange) -> {
			ServerHttpRequest request = exchange.getRequest();
			boolean result = isCandidateForExchange(request) && resolveBasicValue(request, properties)
				.map((basic) -> properties.isUserConfigured(basic.getClientId()))
				.orElse(false);
			return result ? MatchResult.match() : MatchResult.notMatch();
		};
	}

	private static Optional<BasicValue> fromAuthorizationHeader(ServerHttpRequest request) {
		List<String> values = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
		if (values == null) {
			return Optional.empty();
		}
		return values.stream().filter(BasicValue::isBasic).findFirst().flatMap(BasicValue::parse);
	}

	private static Optional<BasicValue> fromQueryParam(ServerHttpRequest request, String parameterName) {
		return Optional.ofNullable(request.getQueryParams().getFirst(parameterName))
			.filter(StringUtils::hasText)
			.flatMap(BasicValue::parseCredentials);
	}

}
