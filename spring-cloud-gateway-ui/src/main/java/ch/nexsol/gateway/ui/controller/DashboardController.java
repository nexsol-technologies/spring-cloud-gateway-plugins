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

import ch.nexsol.gateway.ui.overview.OverviewService;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the gateway UI shell home page: an overview of the gateway built from the
 * figures contributed by every enabled view. The side menu is populated globally by
 * {@link GatewayUiModelAttributes}, so plugin-contributed entries appear automatically.
 */
@Controller
@RequestMapping("/ui")
public class DashboardController {

	private final OverviewService overviewService;

	/**
	 * Creates the controller with the overview aggregation service.
	 * @param overviewService the service gathering the figures shown on the home page
	 */
	public DashboardController(OverviewService overviewService) {
		this.overviewService = overviewService;
	}

	/**
	 * Renders the home page inside the shell.
	 * @param model the view model
	 * @return the home page view name
	 */
	@GetMapping
	public Mono<String> home(Model model) {
		return this.overviewService.stats().doOnNext((stats) -> {
			model.addAttribute("stats", stats);
			model.addAttribute("uptime", this.overviewService.uptimeText());
			model.addAttribute("activeNav", "home");
		}).thenReturn("dashboard/index");
	}

}
