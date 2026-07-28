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

import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the traffic view: the page hosting the bubble chart, and the endpoint that feeds
 * it with the current per-route figures as JSON.
 * <p>
 * The figures come from whichever {@link RouteMetricsSource} is active, and are served
 * with the coverage they were computed over so the page can state whose traffic it is
 * showing.
 */
@Controller
@RequestMapping("/ui/metrics")
public class RouteMetricsController {

	/** Reported when the metrics plugin resolved no source at all. */
	static final String NO_SOURCE = "no metrics source available";

	private final ObjectProvider<RouteMetricsSource> metricsSource;

	/**
	 * Creates the controller over the (optional) active metrics source.
	 * @param metricsSource the provider over the source the per-route figures are read
	 * from
	 */
	public RouteMetricsController(ObjectProvider<RouteMetricsSource> metricsSource) {
		this.metricsSource = metricsSource;
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
	 * Returns the current per-route figures and their coverage, for the chart. A view
	 * whose source is absent reports that it has nothing to show rather than breaking the
	 * page.
	 * @return the current snapshot
	 */
	@GetMapping("/data")
	@ResponseBody
	public Mono<RouteMetricsSnapshot> data() {
		RouteMetricsSource source = this.metricsSource.getIfAvailable();
		return (source != null) ? source.collect() : Mono.just(RouteMetricsSnapshot.empty(NO_SOURCE));
	}

}
