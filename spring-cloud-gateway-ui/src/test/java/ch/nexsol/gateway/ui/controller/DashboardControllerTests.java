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

import ch.nexsol.gateway.ui.nav.NavItem;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureWebTestClient(timeout = "300000")
class DashboardControllerTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldRenderHomePageWithinTheShell() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_HTML)
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("gw-sidebar")
				.contains("id=\"gw-toggle\"")
				.contains("gw-nav-link")
				.contains("Where the gateway stands right now."));
	}

	@Test
	void shouldRenderTheOverviewFiguresAndTheUptime() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("up for").contains("Routes").contains("Views"));
	}

	@Test
	void shouldLinkEveryPluginViewButTheHomeOneFromTheOverview() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("/ui/widgets").contains("/ui/routes/test"));
	}

	@Test
	void shouldRenderBuiltInHomeMenuEntry() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains("#icon-home").contains(">Home</span>"));
	}

	@Test
	void shouldRenderPluginContributedMenuEntry() {
		this.webTestClient.get()
			.uri("/ui")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.value((body) -> assertThat(body).contains(">Widgets</span>").contains("/ui/widgets"));
	}

	/**
	 * Stands in for a plugin lighting up its own menu entry by declaring a
	 * {@link NavItem} bean, mirroring the Spring Boot Admin-style activation.
	 */
	@TestConfiguration
	static class ContributedNavItemConfiguration {

		@Bean
		NavItem widgetsNavItem() {
			return new NavItem("widgets", "Widgets", "icon-plugin", "/ui/widgets", 10);
		}

	}

}
