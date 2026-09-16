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

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The band every view opens with, rendered from the shared fragment.
 * <p>
 * The fragment is called with the icon, the name and the coverage id of each view as
 * plain strings, so a view that mistypes one of them still renders: only the page says
 * so. These tests read the page.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.hub-openapi.enabled=true",
		"spring.cloud.gateway.server.webflux.hub-openapi.gateway-uri=http://localhost:8080",
		"spring.cloud.gateway.server.webflux.pentest.passive.enabled=true" })
@AutoConfigureWebTestClient(timeout = "300000")
class ViewBandTests {

	@Autowired
	WebTestClient webTestClient;

	private static Stream<Arguments> views() {
		return Stream.of(Arguments.of("/ui/routes", "icon-route", "Routes"),
				Arguments.of("/ui/routes/db", "icon-plugin", "Database routes"),
				Arguments.of("/ui/routes/test", "icon-target", "Route tester"),
				Arguments.of("/ui/metrics", "icon-chart", "Traffic"),
				Arguments.of("/ui/metrics/instances", "icon-server", "Runtime"),
				Arguments.of("/ui/service-graph", "icon-graph", "Service graph"),
				Arguments.of("/ui/service-graph/flow", "icon-flow", "Flow"),
				Arguments.of("/ui/audit", "icon-list", "Audit"), Arguments.of("/ui/openapi", "icon-book", "OpenAPI"),
				Arguments.of("/ui/passive-scan", "icon-shield", "Passive scan"),
				Arguments.of("/ui/passive-scan/routes", "icon-award", "Route scores"));
	}

	@ParameterizedTest
	@MethodSource("views")
	void shouldOpenEveryViewWithItsBadgeAndItsName(String uri, String icon, String title) {
		page(uri).value((body) -> assertThat(body).contains("gw-band-badge")
			.contains("href=\"#" + icon + "\"")
			.contains("<h1 class=\"h4 fw-bold mb-0\">" + title + "</h1>"));
	}

	@ParameterizedTest
	@MethodSource("views")
	void shouldCarryTheBadgeOnceOnly(String uri, String icon, String title) {
		// The icon is also the one the side menu draws for the view, so the page holds
		// two
		// references to the same symbol and no more: the band and the menu entry.
		page(uri).value((body) -> assertThat(count(body, "gw-band-badge")).isEqualTo(1));
	}

	/**
	 * The three views reading consolidated figures name the element their script fills
	 * with the coverage on every refresh. A renamed id leaves the line empty, and a
	 * console fronting a cluster then reports figures without saying whose.
	 * @param uri the view
	 * @param id the id of its coverage line
	 */
	@ParameterizedTest
	@MethodSource("coverages")
	void shouldCarryTheCoverageLineOfTheViewsThatConsolidate(String uri, String id) {
		page(uri).value((body) -> assertThat(body).contains("id=\"" + id + "\""));
	}

	private static Stream<Arguments> coverages() {
		return Stream.of(Arguments.of("/ui/metrics", "gm-coverage"),
				Arguments.of("/ui/metrics/instances", "gi-coverage"), Arguments.of("/ui/service-graph", "gg-coverage"),
				Arguments.of("/ui/service-graph/flow", "gf-coverage"));
	}

	/**
	 * A view with nothing to consolidate passes no id, and the line is left out rather
	 * than rendered as an element nothing will ever fill.
	 * @param uri the view
	 * @param icon the icon of its badge
	 * @param title its name
	 */
	@ParameterizedTest
	@MethodSource("views")
	void shouldLeaveTheCoverageLineOutOfTheViewsThatHaveNothingToConsolidate(String uri, String icon, String title) {
		page(uri).value((body) -> assertThat(body).doesNotContain("id=\"\""));
	}

	/**
	 * The runtime view keeps its controls in the card holding the fleet table, below the
	 * band and above the table itself. They are wired once: the script replaces the table
	 * on every refresh, and a control caught in that replacement would lose its listener.
	 */
	@Test
	void shouldDrawTheControlsOfTheRuntimeViewBelowItsBand() {
		page("/ui/metrics/instances").value((body) -> {
			assertThat(count(body, "id=\"gi-refresh\"")).isEqualTo(1);
			assertThat(body.indexOf("id=\"gi-refresh\"")).isGreaterThan(body.indexOf("The technical health"));
			assertThat(body.indexOf("id=\"gi-refresh\"")).isLessThan(body.indexOf("id=\"gi-instances\""));
		});
	}

	/**
	 * The OpenAPI view keeps its controls in the card carrying the reference, above the
	 * element Scalar mounts into.
	 */
	@Test
	void shouldDrawTheControlsOfTheOpenapiViewBelowItsBand() {
		page("/ui/openapi").value((body) -> {
			assertThat(count(body, "id=\"gw-openapi-refresh\"")).isEqualTo(1);
			assertThat(body.indexOf("id=\"gw-openapi-refresh\"")).isGreaterThan(body.indexOf("call them from here"));
			assertThat(body.indexOf("id=\"gw-openapi-refresh\"")).isLessThan(body.indexOf("id=\"gw-openapi\""));
		});
	}

	/**
	 * Nothing but the icon, the name and what the view says about itself sits in the
	 * band, on every view: a control that crept back into one would read as a second
	 * toolbar.
	 * @param uri the view
	 * @param icon the icon of its badge
	 * @param title its name
	 */
	@ParameterizedTest
	@MethodSource("views")
	void shouldKeepEveryControlOutOfTheBand(String uri, String icon, String title) {
		page(uri).value((body) -> {
			int band = body.indexOf("gw-band-badge");
			int endOfBand = body.indexOf("</h1>", band);
			assertThat(body.substring(band, endOfBand)).doesNotContain("<button").doesNotContain("<input");
		});
	}

	@Test
	void shouldOpenTheHomePageWithTheLockupInTheSameBand() {
		page("/ui").value((body) -> assertThat(body).contains("<h1 class=\"h4 fw-bold mb-0\">Spring Cloud Gateway</h1>")
			.contains("gw-home-logo")
			.contains("up for"));
	}

	private static int count(String body, String token) {
		int found = 0;
		for (int at = body.indexOf(token); at >= 0; at = body.indexOf(token, at + token.length())) {
			found++;
		}
		return found;
	}

	private WebTestClient.BodySpec<String, ?> page(String uri) {
		return this.webTestClient.get().uri(uri).exchange().expectStatus().isOk().expectBody(String.class);
	}

}
