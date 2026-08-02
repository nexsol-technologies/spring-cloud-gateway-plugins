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

import java.util.List;

import reactor.core.publisher.Mono;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the routes view: every route definition the gateway resolves, grouped by the
 * source it was read from. This answers which configuration actually won when several
 * sources (properties, database, files, OpenAPI, Config Server) declare routes at once.
 */
@Controller
@RequestMapping("/ui/routes")
public class RouteInventoryController {

	private final RouteInventoryService inventoryService;

	/**
	 * Creates the controller with the route inventory service.
	 * @param inventoryService the service listing the resolved route definitions
	 */
	public RouteInventoryController(RouteInventoryService inventoryService) {
		this.inventoryService = inventoryService;
	}

	/**
	 * Renders the routes page inside the shell.
	 * @param model the view model
	 * @return the page view name
	 */
	@GetMapping
	public Mono<String> page(Model model) {
		return populate(model).thenReturn("dashboard/routes");
	}

	/**
	 * Renders the route table fragment, used to refresh the list in place: it re-reads
	 * the sources instead of serving the cached inventory, so the figures it shows are
	 * the current ones.
	 * @param model the view model
	 * @return the table fragment view name
	 */
	@GetMapping("/list")
	public Mono<String> list(Model model) {
		return populate(model, this.inventoryService.refreshedRoutes())
			.thenReturn("dashboard/fragments/route-inventory :: inventory");
	}

	/**
	 * Asks the gateway to rebuild its route table, then re-renders the table fragment.
	 * @param model the view model
	 * @return the table fragment view name
	 */
	@PostMapping("/reload")
	public Mono<String> reload(Model model) {
		this.inventoryService.reload();
		return populate(model, this.inventoryService.refreshedRoutes())
			.thenReturn("dashboard/fragments/route-inventory :: inventory");
	}

	private Mono<Void> populate(Model model) {
		return populate(model, this.inventoryService.routes());
	}

	private Mono<Void> populate(Model model, Mono<List<RouteView>> routes) {
		return routes.doOnNext((resolved) -> {
			model.addAttribute("routes", resolved);
			model.addAttribute("activeNav", "routes-all");
		}).then();
	}

}
