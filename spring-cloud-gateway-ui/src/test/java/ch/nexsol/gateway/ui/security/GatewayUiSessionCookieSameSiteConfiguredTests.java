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
 * An application setting the {@code SameSite} itself keeps what it set: the console
 * defaults the attribute, it does not take the property over.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret",
		"server.reactive.session.cookie.same-site=strict" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiSessionCookieSameSiteConfiguredTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldLeaveTheSameSiteTheApplicationChose() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectCookie()
			.sameSite(UiSessionCookieName.COOKIE_NAME, "Strict");
	}

}
