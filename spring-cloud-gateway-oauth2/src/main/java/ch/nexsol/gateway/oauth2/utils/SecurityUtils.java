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

import java.util.Base64;
import java.util.Optional;

import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;

public final class SecurityUtils {

	public static final String HEADER_AUTHORIZATION_BASIC = "basic ";

	private SecurityUtils() {

	}

	public static ServerWebExchangeMatcher authorizationHeaderBasicMatcher(
			BasicAuthExchangeToAccessTokenProperties properties) {
		return (exchange) -> {
			ServerHttpRequest request = exchange.getRequest();
			boolean result = Optional.ofNullable(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
				.filter((header) -> header.toLowerCase().startsWith(HEADER_AUTHORIZATION_BASIC))
				.map((basic) -> {
					String pair = basic;
					if (!basic.contains(":")) {
						pair = new String(
								Base64.getDecoder().decode(basic.substring(HEADER_AUTHORIZATION_BASIC.length())));
					}
					String userName = pair.split(":")[0];
					return userName;
				})
				.map((user) -> properties.isUserConfigured(user))
				.orElse(false);
			return result ? MatchResult.match() : MatchResult.notMatch();

		};
	}

}
