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

package ch.nexsol.gateway.ui.servicegraph;

import ch.nexsol.gateway.servicegraph.ServiceGraphSnapshot;
import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the service graph view: the page hosting the graph, and the endpoint that feeds
 * it with the current nodes and edges as JSON.
 * <p>
 * The graph comes from whichever {@link ServiceGraphSource} is active, and is served with
 * the coverage it was computed over so the page can state what it is showing: the calls
 * one instance counted, the calls every instance counted, or the graph a tracing backend
 * derived from the spans.
 */
@Controller
@RequestMapping("/ui/service-graph")
public class ServiceGraphController {

	/** Reported when the service graph plugin resolved no source at all. */
	static final String NO_SOURCE = "no service graph source available";

	private final ObjectProvider<ServiceGraphSource> graphSource;

	/**
	 * Creates the controller over the (optional) active graph source.
	 * @param graphSource the provider over the source the graph is read from
	 */
	public ServiceGraphController(ObjectProvider<ServiceGraphSource> graphSource) {
		this.graphSource = graphSource;
	}

	/**
	 * Renders the service graph page inside the shell.
	 * @param model the view model
	 * @return the page view name
	 */
	@GetMapping
	public String page(Model model) {
		model.addAttribute("activeNav", "service-graph");
		return "dashboard/service-graph";
	}

	/**
	 * Returns the current graph and its coverage. A view whose source is absent reports
	 * that it has nothing to show rather than breaking the page.
	 * @return the current snapshot
	 */
	@GetMapping("/data")
	@ResponseBody
	public Mono<ServiceGraphSnapshot> data() {
		ServiceGraphSource source = this.graphSource.getIfAvailable();
		return (source != null) ? source.collect() : Mono.just(ServiceGraphSnapshot.empty(NO_SOURCE));
	}

}
