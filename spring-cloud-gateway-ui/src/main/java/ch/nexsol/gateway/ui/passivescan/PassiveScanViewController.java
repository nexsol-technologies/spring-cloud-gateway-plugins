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

package ch.nexsol.gateway.ui.passivescan;

import java.util.List;
import java.util.Locale;

import ch.nexsol.gateway.pentest.core.model.AggregatedFinding;
import ch.nexsol.gateway.pentest.core.model.Finding;
import ch.nexsol.gateway.pentest.core.store.FindingStore;
import ch.nexsol.gateway.pentest.core.store.FindingSummary;
import ch.nexsol.gateway.pentest.passive.score.RouteScore;
import ch.nexsol.gateway.pentest.passive.score.RouteScoreService;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the passive-scan view: the findings the analyser raised from live traffic,
 * collapsed to one row per distinct problem with an occurrence count, newest first, with
 * a running summary, and the route scores derived from them. The full pages render inside
 * the shell; the JSON endpoints feed the tables and the counters.
 */
@Controller
@RequestMapping("/ui/passive-scan")
public class PassiveScanViewController {

	private final FindingStore store;

	private final RouteScoreService routeScores;

	public PassiveScanViewController(FindingStore store, RouteScoreService routeScores) {
		this.store = store;
		this.routeScores = routeScores;
	}

	@GetMapping
	public String page(Model model) {
		model.addAttribute("activeNav", "passive-scan");
		return "dashboard/passive-scan";
	}

	@GetMapping("/routes")
	public String routesPage(Model model) {
		model.addAttribute("activeNav", "passive-scan-routes");
		return "dashboard/passive-scan-routes";
	}

	@GetMapping("/routes/data")
	@ResponseBody
	public Mono<List<RouteScore>> routesData() {
		return this.routeScores.scores();
	}

	@GetMapping("/findings")
	@ResponseBody
	public List<FindingView> findings(@RequestParam(required = false) String severity,
			@RequestParam(required = false) String query, @RequestParam(defaultValue = "200") int limit) {
		String wantedSeverity = StringUtils.hasText(severity) ? severity.trim().toUpperCase(Locale.ROOT) : null;
		String needle = StringUtils.hasText(query) ? query.trim().toLowerCase(Locale.ROOT) : null;
		return this.store.recent()
			.stream()
			.filter((aggregate) -> matches(aggregate, wantedSeverity, needle))
			.limit(Math.clamp(limit, 1, 1000))
			.map(FindingView::of)
			.toList();
	}

	@GetMapping("/summary")
	@ResponseBody
	public FindingSummary summary() {
		return this.store.summary();
	}

	@GetMapping("/coverage")
	@ResponseBody
	public java.util.List<ch.nexsol.gateway.pentest.passive.coverage.CategoryCoverage> coverage() {
		return ch.nexsol.gateway.pentest.passive.coverage.PassiveCoverage.categories();
	}

	private static boolean matches(AggregatedFinding aggregate, String wantedSeverity, String needle) {
		Finding finding = aggregate.finding();
		if (wantedSeverity != null && !finding.severity().name().equals(wantedSeverity)) {
			return false;
		}
		if (needle == null) {
			return true;
		}
		return contains(finding.scannerId(), needle) || contains(finding.path(), needle)
				|| contains(finding.detail(), needle) || contains(finding.category().getCode(), needle)
				|| contains(finding.cwe(), needle);
	}

	private static boolean contains(String value, String needle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
	}

}
