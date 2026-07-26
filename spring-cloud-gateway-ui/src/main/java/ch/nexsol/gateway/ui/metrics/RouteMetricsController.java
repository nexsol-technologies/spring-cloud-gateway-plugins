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

package ch.nexsol.gateway.ui.metrics;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the traffic view: a bubble chart of the gateway routes plotted from their
 * request metrics. The full page renders inside the shell, and the {@code /data} endpoint
 * feeds the chart with the current per-route metrics as JSON.
 */
@Controller
@RequestMapping("/ui/metrics")
public class RouteMetricsController {

	private final RouteMetricsService metricsService;

	/**
	 * Creates the controller with the metrics aggregation service.
	 * @param metricsService the service aggregating the per-route request metrics
	 */
	public RouteMetricsController(RouteMetricsService metricsService) {
		this.metricsService = metricsService;
	}

	/**
	 * Renders the traffic chart page inside the shell.
	 * @param model the view model
	 * @return the page view name
	 */
	@GetMapping
	public String page(Model model) {
		model.addAttribute("activeNav", "traffic");
		return "dashboard/metrics";
	}

	/**
	 * Returns the current per-route metrics as JSON for the chart.
	 * @return the aggregated per-route metrics
	 */
	@GetMapping("/data")
	@ResponseBody
	public List<RouteMetric> data() {
		return this.metricsService.collect();
	}

}
