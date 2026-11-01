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

package ch.nexsol.gateway.ui.security;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

/**
 * Keeps the session cookie of the console out of the requests the gateway sends to the
 * services it routes to.
 * <p>
 * The cookie is scoped to the origin rather than to {@code /ui}:
 * {@code CookieWebSessionIdResolver} gives it the context path, and the console is not
 * served from one prefix anyway &mdash; the OpenID Connect round trip, the assets of the
 * shell and the pages of the OpenAPI hub all sit outside {@code /ui}. The browser
 * therefore attaches it to every call a routed API serves on that origin, and
 * {@code Cookie} is not a hop-by-hop header, so without this the id an operator signed in
 * with reaches every backend and its access logs.
 * <p>
 * The headers are filtered rather than the exchange: a {@code GlobalFilter} mutating the
 * request would also take the cookie away from the gateway's own session handling, which
 * reads it from the exchange to decide whether the session it names still exists.
 * <p>
 * Only the name the console gives the cookie is removed. An application that named it
 * itself through {@code server.reactive.session.cookie.name} is the one that may be
 * handing that session to its services on purpose, through a store they share.
 */
public class UiSessionCookieStripper implements HttpHeadersFilter {

	@Override
	public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
		List<String> cookies = input.get(HttpHeaders.COOKIE);
		if (cookies == null || cookies.stream().noneMatch((value) -> value.contains(UiSessionCookieName.COOKIE_NAME))) {
			return input;
		}
		List<String> kept = new ArrayList<>(cookies.size());
		for (String value : cookies) {
			String remaining = without(value);
			if (StringUtils.hasText(remaining)) {
				kept.add(remaining);
			}
		}
		HttpHeaders filtered = new HttpHeaders();
		filtered.addAll(input);
		if (kept.isEmpty()) {
			filtered.remove(HttpHeaders.COOKIE);
		}
		else {
			filtered.put(HttpHeaders.COOKIE, kept);
		}
		return filtered;
	}

	/**
	 * One {@code Cookie} header with the cookie of the console taken out of it.
	 * @param header the header as the browser sent it
	 * @return the remaining cookies, empty when the console held the only one
	 */
	private static String without(String header) {
		StringJoiner kept = new StringJoiner("; ");
		for (String pair : header.split(";")) {
			String cookie = pair.trim();
			// Matched on the name alone, not on the header: a cookie whose value happens
			// to carry the text, or one named after it with a suffix, is somebody else's.
			if (!cookie.isEmpty() && !cookie.equals(UiSessionCookieName.COOKIE_NAME)
					&& !cookie.startsWith(UiSessionCookieName.COOKIE_NAME + "=")) {
				kept.add(cookie);
			}
		}
		return kept.toString();
	}

}
