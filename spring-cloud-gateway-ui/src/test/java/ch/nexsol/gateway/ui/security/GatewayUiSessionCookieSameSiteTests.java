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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The session cookie of the console carries a {@code SameSite}, which Spring Boot's own
 * cookie initializer would otherwise leave off.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiSessionCookieSameSiteTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldGiveTheSessionCookieASameSite() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectCookie()
			.exists(UiSessionCookieName.COOKIE_NAME)
			.expectCookie()
			.sameSite(UiSessionCookieName.COOKIE_NAME, UiSessionCookieName.SAME_SITE)
			.expectCookie()
			.httpOnly(UiSessionCookieName.COOKIE_NAME, true);
	}

}
