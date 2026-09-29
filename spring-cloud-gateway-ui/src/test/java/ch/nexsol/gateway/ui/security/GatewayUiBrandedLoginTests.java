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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The login page with a logo configured in place of the lockup, and only the light one,
 * under no name: the logo of the organisation on both themes, the plugins signing the
 * foot of the card, and the logo itself reachable without a principal, since the page it
 * illustrates is.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.user.name=operator",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret",
		"spring.cloud.gateway.server.webflux.ui.branding.logo=/img/acme.png" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiBrandedLoginTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldDrawTheConfiguredLogoOnBothThemes() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("class=\"gw-brand-mark gw-logo-light\" src=\"/img/acme.png\"")
				// No name configured: the image keeps a text alternative all the same.
				.contains("alt=\"Logo\"")
				.contains("class=\"gw-brand-mark gw-logo-dark\" src=\"/img/acme.png\"")
				.doesNotContain("/img/logo.png")
				.doesNotContain("/img/logo-dark.png"));
	}

	@Test
	void shouldSignTheFootOfTheCard() {
		this.webTestClient.get()
			.uri("/ui/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("class=\"gw-login-signature")
				.contains("Powered by")
				.contains("<span class=\"fw-semibold\">neXsol Technologies</span>")
				.contains("class=\"gw-signature-icon\" src=\"/img/icon.png\""));
	}

	@Test
	void shouldServeTheConfiguredLogoWithoutAPrincipal() {
		this.webTestClient.get().uri("/img/acme.png").exchange().expectStatus().isOk();
	}

	@Test
	void shouldStillSendAnAnonymousVisitorToTheLoginPage() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isFound()
			.expectHeader()
			.location("/ui/login?unauthorized");
	}

}
