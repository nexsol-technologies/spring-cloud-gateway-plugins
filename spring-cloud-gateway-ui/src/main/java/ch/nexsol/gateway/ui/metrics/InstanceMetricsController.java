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

import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the instances view: the technical health of every gateway instance, read from
 * whatever {@link InstanceMetricsSource} the metrics plugin resolved.
 */
@Controller
@RequestMapping("/ui/metrics/instances")
public class InstanceMetricsController {

	/** Reported when the metrics plugin resolved no instance source at all. */
	static final String NO_SOURCE = "no instance metrics source available";

	private final ObjectProvider<InstanceMetricsSource> instanceMetricsSource;

	/**
	 * Creates the controller over the (optional) active instance metrics source.
	 * @param instanceMetricsSource the provider over the source the per-instance figures
	 * are read from
	 */
	public InstanceMetricsController(ObjectProvider<InstanceMetricsSource> instanceMetricsSource) {
		this.instanceMetricsSource = instanceMetricsSource;
	}

	/**
	 * Renders the instances page inside the shell.
	 * @param model the view model
	 * @return the page view name
	 */
	@GetMapping
	public String page(Model model) {
		model.addAttribute("activeNav", "instances");
		return "dashboard/instances";
	}

	/**
	 * Returns the current per-instance figures and their coverage. A view whose source is
	 * absent reports that it has nothing to show rather than breaking the page.
	 * @return the current snapshot
	 */
	@GetMapping("/data")
	@ResponseBody
	public Mono<InstanceMetricsSnapshot> data() {
		InstanceMetricsSource source = this.instanceMetricsSource.getIfAvailable();
		return (source != null) ? source.collect() : Mono.just(InstanceMetricsSnapshot.empty(NO_SOURCE));
	}

}
