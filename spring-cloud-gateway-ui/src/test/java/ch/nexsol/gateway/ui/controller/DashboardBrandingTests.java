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

package ch.nexsol.gateway.ui.controller;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The home page with a logo configured in place of the lockup: the logo of the
 * organisation in the band, the plugins signing it under the uptime, and no lockup.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.branding.logo=/img/acme.png",
		"spring.cloud.gateway.server.webflux.ui.branding.logo-dark=/img/acme-dark.png",
		"spring.cloud.gateway.server.webflux.ui.branding.name=Acme" })
@AutoConfigureWebTestClient(timeout = "300000")
class DashboardBrandingTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldDrawTheConfiguredLogoInPlaceOfTheLockup() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("class=\"gw-brand-mark gw-logo-light\" src=\"/img/acme.png\"")
				.contains("alt=\"Acme\"")
				.contains("class=\"gw-brand-mark gw-logo-dark\" src=\"/img/acme-dark.png\"")
				.doesNotContain("/img/logo.png")
				.doesNotContain("/img/logo-dark.png"));
	}

	@Test
	void shouldSignTheBandUnderTheUptime() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("class=\"gw-powered mt-1\"")
				.contains("powered by <span class=\"fw-semibold\">neXsol</span>")
				.contains("class=\"gw-signature-icon\" src=\"/img/icon.png\""));
	}

	@Test
	void shouldKeepTheIconOfTheSideMenu() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("class=\"gw-brand-logo\" src=\"/img/icon.png\""));
	}

	@Test
	void shouldServeTheConfiguredLogos() {
		for (String image : new String[] { "/img/acme.png", "/img/acme-dark.png" }) {
			this.webTestClient.get()
				.uri(image)
				.exchange()
				.expectStatus()
				.isOk()
				.expectHeader()
				.contentType(MediaType.IMAGE_PNG);
		}
	}

}
