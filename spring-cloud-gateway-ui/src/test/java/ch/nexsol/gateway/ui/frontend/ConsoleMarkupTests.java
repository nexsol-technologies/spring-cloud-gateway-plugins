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

package ch.nexsol.gateway.ui.frontend;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the pages of the console hand the browser, read as markup: the things a script or
 * a keyboard depends on and that no assertion on a status would catch.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.pentest.passive.enabled=true" })
@AutoConfigureWebTestClient(timeout = "300000")
class ConsoleMarkupTests {

	@Autowired
	WebTestClient webTestClient;

	/**
	 * The band is the extension point every view opens with. A name handed to it is text,
	 * whatever it contains: the first view to pass a route id or a service name must not
	 * be handing markup to the page.
	 */
	@Test
	void theBandRendersWhatItIsGivenAsText() {
		String body = page("/band-probe");

		assertThat(body).doesNotContain("<img src=x").doesNotContain("<b>bold</b>");
		assertThat(body).contains("&lt;img src=x onerror=alert(1)&gt;").contains("&lt;b&gt;bold&lt;/b&gt;");
	}

	@Test
	void theBandStillRendersThePlainSubtitleOfAView() {
		assertThat(page("/ui/routes")).contains("Every route the gateway resolves, and the source it comes from")
			.contains("so you can tell which configuration actually won.");
	}

	/**
	 * An export is answered as an attachment, so the page never unloads: without the
	 * {@code download} attribute the shell starts the progress bar of a navigation that
	 * never happens, and nothing ever stops it.
	 */
	@Test
	void theExportLinksOfThePassiveScanAreDownloads() {
		String body = page("/ui/passive-scan");

		Matcher links = Pattern.compile("<a[^>]*href=\"[^\"]*/passive-scan/export[^\"]*\"[^>]*>").matcher(body);
		int found = 0;
		while (links.find()) {
			found++;
			assertThat(links.group()).contains("download");
		}
		assertThat(found).isEqualTo(3);
	}

	/**
	 * The row of axis pickers of the traffic view is a Bootstrap flex utility, whose
	 * {@code !important} wins over an inline {@code display}: hidden by a style, it was
	 * never hidden at all.
	 */
	@Test
	void theCustomAxisPickersOfTheTrafficViewStartHiddenByAClass() {
		String body = page("/ui/metrics");

		Matcher pickers = Pattern.compile("<div id=\"gm-custom\"[^>]*>").matcher(body);
		assertThat(pickers.find()).isTrue();
		assertThat(pickers.group()).contains("d-none").doesNotContain("style=");
	}

	/**
	 * A predicate or filter row of the database routes form: the selector says what it
	 * selects, and each argument label names the input it labels.
	 */
	@Test
	void theRowsOfTheRouteFormAreLabelled() {
		String body = page("/ui/routes/db");

		Matcher selector = Pattern.compile("<select[^>]*name=\"predicates\\[[^\"]+\\]\\.name\"[^>]*>").matcher(body);
		assertThat(selector.find()).isTrue();
		assertThat(selector.group()).contains("aria-label=\"Predicate\"")
			.contains("hx-get=\"/ui/routes/db/element-args/predicate/");
	}

	@Test
	void theArgumentsOfARouteFormRowAreLabelled() {
		String body = this.webTestClient.get()
			.uri((builder) -> builder.path("/ui/routes/db/element-args/predicate/7")
				.queryParam("predicates[7].name", "Path")
				.build())
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();

		Matcher label = Pattern.compile("<label[^>]*for=\"([^\"]+)\"[^>]*>patterns</label>").matcher(body);
		assertThat(label.find()).isTrue();
		assertThat(body).contains("id=\"" + label.group(1) + "\"");
	}

	private String page(String uri) {
		return this.webTestClient.get()
			.uri(uri)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class BandProbe {

		@Bean
		BandProbeController bandProbeController() {
			return new BandProbeController();
		}

	}

	/**
	 * A view handing the band a name that is not its own to choose, as one built from a
	 * route id or a service name would.
	 */
	@Controller
	static class BandProbeController {

		@GetMapping("/band-probe")
		String probe(Model model) {
			model.addAttribute("title", "<img src=x onerror=alert(1)>");
			model.addAttribute("subtitle", "<b>bold</b>");
			return "dashboard/band-probe";
		}

	}

}
