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

package ch.nexsol.gateway.ui.routes;

import reactor.core.publisher.Mono;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;

/**
 * Serves the route tester: a request is described rather than sent, and the gateway route
 * table answers which route would handle it and why. Nothing is forwarded to a downstream
 * service.
 */
@Controller
@RequestMapping("/ui/routes/test")
public class RouteTesterController {

	private final RouteTesterService testerService;

	/**
	 * Creates the controller with the route tester service.
	 * @param testerService the service evaluating a request against the route table
	 */
	public RouteTesterController(RouteTesterService testerService) {
		this.testerService = testerService;
	}

	/**
	 * Renders the tester page, with an empty form, inside the shell.
	 * @param model the view model
	 * @return the page view name
	 */
	@GetMapping
	public String page(Model model) {
		model.addAttribute("activeNav", "route-tester");
		return "dashboard/route-tester";
	}

	/**
	 * Evaluates the request described by the submitted form and renders the result
	 * fragment.
	 * <p>
	 * The form fields are read off the exchange rather than through
	 * {@code @RequestParam}, which in WebFlux only ever looks at the query string.
	 * @param exchange the exchange carrying the submitted form
	 * @param model the view model
	 * @return the result fragment view name
	 */
	@PostMapping
	public Mono<String> run(ServerWebExchange exchange, Model model) {
		return exchange.getFormData()
			.flatMap((form) -> this.testerService.test(form.getFirst("method"), form.getFirst("path"),
					form.getFirst("headers")))
			.doOnNext((report) -> {
				model.addAttribute("report", report);
				model.addAttribute("activeNav", "route-tester");
			})
			.thenReturn("dashboard/fragments/route-test-result :: result");
	}

}
