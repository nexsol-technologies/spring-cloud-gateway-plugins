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

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the cookie of the console is taken out of what the gateway sends downstream,
 * and that nothing else is.
 */
class UiSessionCookieStripperTests {

	private final UiSessionCookieStripper stripper = new UiSessionCookieStripper();

	@Test
	void removesTheCookieOfTheConsoleAndKeepsTheOthers() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, "locale=fr; " + UiSessionCookieName.COOKIE_NAME + "=an-operator; SESSION=app");
		headers.add(HttpHeaders.AUTHORIZATION, "Bearer a-token");

		HttpHeaders filtered = this.stripper.filter(headers, exchange());

		assertThat(filtered.get(HttpHeaders.COOKIE)).containsExactly("locale=fr; SESSION=app");
		assertThat(filtered.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer a-token");
	}

	@Test
	void removesTheHeaderAltogetherWhenTheConsoleHeldTheOnlyCookie() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, UiSessionCookieName.COOKIE_NAME + "=an-operator");

		HttpHeaders filtered = this.stripper.filter(headers, exchange());

		assertThat(filtered.containsHeader(HttpHeaders.COOKIE)).isFalse();
	}

	@Test
	void clearsEveryCookieHeaderTheRequestCarries() {
		// HTTP/2 lets a client split its cookies over several headers.
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, UiSessionCookieName.COOKIE_NAME + "=an-operator");
		headers.add(HttpHeaders.COOKIE, "SESSION=app; " + UiSessionCookieName.COOKIE_NAME + "=an-operator");

		HttpHeaders filtered = this.stripper.filter(headers, exchange());

		assertThat(filtered.get(HttpHeaders.COOKIE)).containsExactly("SESSION=app");
	}

	@Test
	void leavesARequestCarryingNoCookieOfTheConsoleUntouched() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, "locale=fr; SESSION=app");

		HttpHeaders filtered = this.stripper.filter(headers, exchange());

		assertThat(filtered).isSameAs(headers);
	}

	@Test
	void leavesARequestCarryingNoCookieAtAllUntouched() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.AUTHORIZATION, "Bearer a-token");

		HttpHeaders filtered = this.stripper.filter(headers, exchange());

		assertThat(filtered).isSameAs(headers);
	}

	@Test
	void keepsACookieNamedAfterTheConsoleWithSomethingAppended() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, UiSessionCookieName.COOKIE_NAME + "_PREVIOUS=an-operator");

		HttpHeaders filtered = this.stripper.filter(headers, exchange());

		assertThat(filtered.get(HttpHeaders.COOKIE))
			.containsExactly(UiSessionCookieName.COOKIE_NAME + "_PREVIOUS=an-operator");
	}

	@Test
	void keepsACookieWhoseValueCarriesTheNameOfTheConsole() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, "last-page=" + UiSessionCookieName.COOKIE_NAME);

		HttpHeaders filtered = this.stripper.filter(headers, exchange());

		assertThat(filtered.get(HttpHeaders.COOKIE)).containsExactly("last-page=" + UiSessionCookieName.COOKIE_NAME);
	}

	private static ServerWebExchange exchange() {
		return MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders"));
	}

}
