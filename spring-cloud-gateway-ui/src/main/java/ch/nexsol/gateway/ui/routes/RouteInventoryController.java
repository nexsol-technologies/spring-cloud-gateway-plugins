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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the routes view: every route definition the gateway resolves, grouped by the
 * source it was read from. This answers which configuration actually won when several
 * sources (properties, database, files, OpenAPI, Config Server) declare routes at once.
 */
@Controller
@RequestMapping("/ui/routes")
public class RouteInventoryController {

	/**
	 * RFC 9512 registers this for YAML; 'text/yaml' predates it and is not registered.
	 */
	private static final MediaType YAML = new MediaType("application", "yaml", StandardCharsets.UTF_8);

	private static final String FILE_NAME = "gateway-routes.yml";

	private final RouteInventoryService inventoryService;

	/**
	 * Creates the controller with the route inventory service.
	 * @param inventoryService the service listing the resolved route definitions
	 */
	public RouteInventoryController(RouteInventoryService inventoryService) {
		this.inventoryService = inventoryService;
	}

	/**
	 * Renders the routes page inside the shell, on a fresh read of every source rather
	 * than on the cached inventory. This view answers which configuration won
	 * <em>now</em>: served from the cache it showed the sources as they stood at some
	 * earlier read, so a service that had just registered was missing from the page that
	 * is opened to look for it.
	 * @param model the view model
	 * @return the page view name
	 */
	@GetMapping
	public Mono<String> page(Model model) {
		return populate(model, this.inventoryService.refreshedRoutes()).thenReturn("dashboard/routes");
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

	/**
	 * Writes the resolved routes back out as gateway configuration, for the sources asked
	 * for. The document is the routes as the gateway holds them now, which is why it is
	 * built on a fresh read rather than on the cached inventory.
	 * @param sources the source names to include; every source when none is given
	 * @return the YAML document, as a download
	 */
	@GetMapping("/export")
	public Mono<ResponseEntity<String>> export(@RequestParam(name = "source", required = false) List<String> sources) {
		return this.inventoryService.definitionsBySource().map((bySource) -> {
			String yaml = RouteDefinitionYaml.write(selected(bySource, sources));
			ContentDisposition disposition = ContentDisposition.attachment().filename(FILE_NAME).build();
			return ResponseEntity.ok()
				.contentType(YAML)
				.headers((headers) -> headers.setContentDisposition(disposition))
				.body(yaml);
		});
	}

	/**
	 * The definitions of the requested sources, in the order the sources were read, so
	 * the exported file lists the routes in the order the gateway matches them. An empty
	 * or absent selection means every source: a download asking for nothing in particular
	 * is asking for the whole configuration.
	 */
	private static List<RouteDefinition> selected(Map<String, List<RouteDefinition>> bySource, List<String> sources) {
		List<RouteDefinition> selected = new ArrayList<>();
		bySource.forEach((source, definitions) -> {
			if (sources == null || sources.isEmpty() || sources.contains(source)) {
				selected.addAll(definitions);
			}
		});
		return selected;
	}

	private Mono<Void> populate(Model model, Mono<List<RouteView>> routes) {
		return routes.doOnNext((resolved) -> {
			model.addAttribute("routes", resolved);
			model.addAttribute("activeNav", "routes-all");
		}).then();
	}

}
