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

package ch.nexsol.gateway.database.controller;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.nexsol.gateway.database.exception.FiltersNotValidException;
import ch.nexsol.gateway.database.exception.PredicatesNotValidException;
import ch.nexsol.gateway.database.exception.RouteAlreadyExistException;
import ch.nexsol.gateway.database.exception.RouteNotFoundException;
import ch.nexsol.gateway.database.model.FilterCreateModel;
import ch.nexsol.gateway.database.model.PredicateCreateModel;
import ch.nexsol.gateway.database.model.RouteCreateModel;
import ch.nexsol.gateway.database.service.ApiService;
import ch.nexsol.gateway.database.service.GatewayConfigService;
import ch.nexsol.gateway.database.service.PredicateArgsFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;

/**
 * Server-rendered Thymeleaf/HTMX user interface for managing gateway routes, backed by
 * the same {@link ApiService} and {@link GatewayConfigService} that power the REST API.
 * It serves the full page under {@code /ui/routes/db}, rendered inside the gateway UI
 * shell, and the HTMX fragments used to refresh the route list, build the form and load
 * predicate/filter arguments dynamically.
 */
@Controller
@RequestMapping("/ui/routes/db")
public class RouteViewController {

	private static final Logger LOG = LoggerFactory.getLogger(RouteViewController.class);

	private static final Pattern INDEX_TOKEN = Pattern.compile("[0-9]+");

	private final ApiService apiService;

	private final GatewayConfigService gatewayConfigService;

	/**
	 * Monotonic generator of unique row indices used to name the dynamically added
	 * predicate and filter form fields.
	 */
	private final AtomicLong rowIndex = new AtomicLong();

	/**
	 * Creates the view controller with the services it delegates to.
	 * @param apiService the facade service exposing route CRUD operations
	 * @param gatewayConfigService the service listing the available predicates and
	 * filters
	 */
	public RouteViewController(ApiService apiService, GatewayConfigService gatewayConfigService) {
		this.apiService = apiService;
		this.gatewayConfigService = gatewayConfigService;
	}

	/**
	 * Renders the full management page: the route list and an empty creation form.
	 * @param model the view model
	 * @return the page view name
	 */
	@GetMapping
	public Mono<String> index(Model model) {
		return addNames(model).then(reloadRoutes(model)).then(Mono.fromCallable(() -> {
			populateEmptyForm(model);
			model.addAttribute("activeNav", "routes");
			return "routes";
		}));
	}

	/**
	 * Renders the route list fragment used to refresh the list after a mutation.
	 * @param model the view model
	 * @return the list fragment view name
	 */
	@GetMapping("/list")
	public Mono<String> list(Model model) {
		return reloadRoutes(model).thenReturn("fragments/route-list :: list");
	}

	/**
	 * Renders a fresh, empty route form (used by the create tab and the edit cancel
	 * button).
	 * @param model the view model
	 * @return the form fragment view name
	 */
	@GetMapping("/new")
	public Mono<String> newForm(Model model) {
		return addNames(model).then(Mono.fromCallable(() -> {
			populateEmptyForm(model);
			return "fragments/route-form :: form";
		}));
	}

	/**
	 * Renders the route form pre-filled with an existing route for editing.
	 * @param id the id of the route to edit
	 * @param model the view model
	 * @return the form fragment view name
	 */
	@GetMapping("/{id}/edit")
	public Mono<String> editForm(@PathVariable Long id, Model model) {
		return addNames(model).then(this.apiService.findById(id)).doOnNext((route) -> {
			model.addAttribute("editing", true);
			model.addAttribute("formRouteId", route.id());
			model.addAttribute("routeId", route.routeId());
			model.addAttribute("uri", route.uri());
			model.addAttribute("order", (route.order() != null) ? route.order() : "");
			model.addAttribute("publicRoute", route.publicRoute());
			model.addAttribute("predicateRows",
					route.predicates()
						.stream()
						.map((p) -> new ElementRowView(this.rowIndex.incrementAndGet(), p.name(),
								new LinkedHashMap<>(p.args())))
						.toList());
			model.addAttribute("filterRows",
					route.filters()
						.stream()
						.map((f) -> new ElementRowView(this.rowIndex.incrementAndGet(), f.name(),
								new LinkedHashMap<>(f.args())))
						.toList());
		}).thenReturn("fragments/route-form :: form");
	}

	/**
	 * Renders a new, empty predicate row appended to the form.
	 * @param model the view model
	 * @return the row fragment view name
	 */
	@GetMapping("/predicate-row")
	public Mono<String> predicateRow(Model model) {
		return addNames(model).then(Mono.fromCallable(() -> {
			model.addAttribute("kind", "predicate");
			model.addAttribute("names", model.getAttribute("predicateNames"));
			model.addAttribute("row", new ElementRowView(this.rowIndex.incrementAndGet(), null, new LinkedHashMap<>()));
			return "fragments/element :: row";
		}));
	}

	/**
	 * Renders a new, empty filter row appended to the form.
	 * @param model the view model
	 * @return the row fragment view name
	 */
	@GetMapping("/filter-row")
	public Mono<String> filterRow(Model model) {
		return addNames(model).then(Mono.fromCallable(() -> {
			model.addAttribute("kind", "filter");
			model.addAttribute("names", model.getAttribute("filterNames"));
			model.addAttribute("row", new ElementRowView(this.rowIndex.incrementAndGet(), null, new LinkedHashMap<>()));
			return "fragments/element :: row";
		}));
	}

	/**
	 * Renders the argument input fields accepted by the selected predicate or filter.
	 * HTMX includes the triggering {@code <select>} value under its own indexed field
	 * name, from which the selected element name is read.
	 * @param kind the element kind, {@code predicate} or {@code filter}
	 * @param index the row index the arguments belong to
	 * @param exchange the current server exchange carrying the selected element name
	 * @param model the view model
	 * @return the arguments fragment view name
	 */
	@GetMapping("/element-args/{kind}/{index}")
	public Mono<String> elementArgs(@PathVariable String kind, @PathVariable long index, ServerWebExchange exchange,
			Model model) {
		String selected = exchange.getRequest().getQueryParams().getFirst(kind + "s[" + index + "].name");
		String name = (selected != null) ? selected : "";
		Map<String, String> args = "filter".equals(kind) ? this.gatewayConfigService.getDefaultArgsForFilter(name)
				: this.gatewayConfigService.getDefaultArgsForPredicate(name);
		Set<String> required = "filter".equals(kind) ? this.gatewayConfigService.getRequiredArgsForFilter(name)
				: this.gatewayConfigService.getRequiredArgsForPredicate(name);
		model.addAttribute("kind", kind);
		model.addAttribute("index", index);
		model.addAttribute("args", args);
		model.addAttribute("requiredArgs", required);
		return Mono.just("fragments/element :: args");
	}

	/**
	 * Creates a route (when the hidden {@code id} field is blank) or updates an existing
	 * one, then returns the fresh form together with an out-of-band refresh of the route
	 * list. On failure the form is re-rendered with the submitted values and an error
	 * message.
	 * @param exchange the current server exchange carrying the submitted form data
	 * @param model the view model
	 * @return the combined form and list fragment on success, or the form fragment on
	 * failure
	 */
	@PostMapping
	public Mono<String> save(ServerWebExchange exchange, Model model) {
		return exchange.getFormData().flatMap((form) -> {
			String idParam = form.getFirst("id");
			boolean editing = idParam != null && !idParam.isBlank();
			RouteCreateModel routeModel;
			try {
				routeModel = buildRouteModel(form);
			}
			catch (RuntimeException ex) {
				return renderFormError(exchange, model, form, editing, "Invalid route: " + ex.getMessage());
			}
			if (routeModel.predicates().isEmpty()) {
				// The REST API enforces this through bean validation on the request body;
				// the
				// UI builds the model by hand, so the same rule is checked explicitly
				// here.
				return renderFormError(exchange, model, form, editing, "A route must have at least one predicate.");
			}
			Mono<?> operation = editing ? this.apiService.updateRoute(Long.valueOf(idParam.trim()), routeModel)
					: this.apiService.createRoute(routeModel);
			return operation.then(renderSaved(exchange, model, editing ? "Route updated" : "Route created"))
				.onErrorResume((ex) -> {
					LOG.error("Failed to save route '{}' from the UI", form.getFirst("routeId"), ex);
					return renderFormError(exchange, model, form, editing, friendlyMessage(ex));
				});
		});
	}

	/**
	 * Deletes the route with the given id and returns the refreshed route list.
	 * @param id the id of the route to delete
	 * @param exchange the current server exchange
	 * @param model the view model
	 * @return the list fragment view name
	 */
	@DeleteMapping("/{id}")
	public Mono<String> delete(@PathVariable Long id, ServerWebExchange exchange, Model model) {
		return this.apiService.deleteRoute(id).then(reloadRoutes(model)).then(Mono.fromCallable(() -> {
			triggerToast(exchange, "success", "Route deleted");
			return "fragments/route-list :: list";
		}));
	}

	private Mono<String> renderSaved(ServerWebExchange exchange, Model model, String message) {
		triggerToast(exchange, "success", message);
		return addNames(model).then(reloadRoutes(model)).then(Mono.fromCallable(() -> {
			populateEmptyForm(model);
			model.addAttribute("oob", true);
			return "fragments/route-form :: saved";
		}));
	}

	private Mono<String> renderFormError(ServerWebExchange exchange, Model model, MultiValueMap<String, String> form,
			boolean editing, String message) {
		triggerToast(exchange, "error", message);
		return addNames(model).then(Mono.fromCallable(() -> {
			model.addAttribute("editing", editing);
			model.addAttribute("formRouteId", editing ? form.getFirst("id") : "");
			model.addAttribute("routeId", form.getFirst("routeId"));
			model.addAttribute("uri", form.getFirst("uri"));
			model.addAttribute("order", form.getFirst("order"));
			model.addAttribute("publicRoute", Boolean.parseBoolean(form.getFirst("public")));
			model.addAttribute("predicateRows", buildRows(form, "predicate"));
			model.addAttribute("filterRows", buildRows(form, "filter"));
			model.addAttribute("formError", message);
			return "fragments/route-form :: form";
		}));
	}

	private Mono<Void> addNames(Model model) {
		return Mono
			.zip(this.gatewayConfigService.getAvailablePredicates().map(CharSequence::toString).collectList(),
					this.gatewayConfigService.getAvailableFilters().map(CharSequence::toString).collectList())
			.doOnNext((tuple) -> {
				model.addAttribute("predicateNames", tuple.getT1().stream().sorted().toList());
				model.addAttribute("filterNames", tuple.getT2().stream().sorted().toList());
			})
			.then();
	}

	private Mono<Void> reloadRoutes(Model model) {
		return this.apiService.getAllRoutes()
			.collectList()
			.doOnNext((routes) -> model.addAttribute("routes", routes))
			.then();
	}

	private void populateEmptyForm(Model model) {
		model.addAttribute("editing", false);
		model.addAttribute("formRouteId", "");
		model.addAttribute("routeId", "");
		model.addAttribute("uri", "");
		model.addAttribute("order", "");
		model.addAttribute("publicRoute", false);
		model.addAttribute("predicateRows",
				List.of(new ElementRowView(this.rowIndex.incrementAndGet(), null, new LinkedHashMap<>())));
		model.addAttribute("filterRows", List.of());
	}

	private RouteCreateModel buildRouteModel(MultiValueMap<String, String> form) {
		String orderValue = form.getFirst("order");
		Integer order = (orderValue != null && !orderValue.isBlank()) ? Integer.valueOf(orderValue.trim()) : null;
		List<PredicateCreateModel> predicates = buildRows(form, "predicate").stream()
			.filter((row) -> row.name() != null && !row.name().isBlank())
			.map((row) -> new PredicateCreateModel(row.name(), nonBlankArgs(row.args())))
			.toList();
		List<FilterCreateModel> filters = buildRows(form, "filter").stream()
			.filter((row) -> row.name() != null && !row.name().isBlank())
			.map((row) -> new FilterCreateModel(row.name(), nonBlankArgs(row.args())))
			.toList();
		boolean publicRoute = Boolean.parseBoolean(form.getFirst("public"));
		return new RouteCreateModel(form.getFirst("routeId"), URI.create(form.getFirst("uri")), order, predicates,
				filters, publicRoute);
	}

	private List<ElementRowView> buildRows(MultiValueMap<String, String> form, String kind) {
		String prefix = Pattern.quote(kind + "s");
		Pattern namePattern = Pattern.compile("^" + prefix + "\\[(.+?)\\]\\.name$");
		Pattern argPattern = Pattern.compile("^" + prefix + "\\[(.+?)\\]\\.args\\[(.+?)\\]$");
		LinkedHashMap<String, RowAccumulator> rows = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : form.entrySet()) {
			Matcher nameMatcher = namePattern.matcher(entry.getKey());
			if (nameMatcher.matches()) {
				rows.computeIfAbsent(nameMatcher.group(1), (token) -> new RowAccumulator()).name = entry.getValue()
					.get(0);
				continue;
			}
			Matcher argMatcher = argPattern.matcher(entry.getKey());
			if (argMatcher.matches()) {
				rows.computeIfAbsent(argMatcher.group(1), (token) -> new RowAccumulator()).args.put(argMatcher.group(2),
						entry.getValue().get(0));
			}
		}
		return rows.entrySet()
			.stream()
			.map((entry) -> new ElementRowView(indexOf(entry.getKey()), entry.getValue().name, entry.getValue().args))
			.toList();
	}

	private long indexOf(String token) {
		return INDEX_TOKEN.matcher(token).matches() ? Long.parseLong(token) : this.rowIndex.incrementAndGet();
	}

	private static Map<String, String> nonBlankArgs(Map<String, String> args) {
		LinkedHashMap<String, String> filtered = new LinkedHashMap<>();
		args.forEach((key, value) -> {
			if (value != null && !value.isBlank()) {
				filtered.put(key, value);
			}
		});
		return filtered;
	}

	private static String friendlyMessage(Throwable ex) {
		if (ex instanceof RouteAlreadyExistException) {
			return "A route with this id already exists.";
		}
		if (ex instanceof PredicatesNotValidException || ex instanceof PredicateArgsFormatException) {
			return "One or more predicates have missing or invalid arguments.";
		}
		if (ex instanceof FiltersNotValidException) {
			return "One or more filters have missing or invalid arguments.";
		}
		if (ex instanceof RouteNotFoundException) {
			return "Route not found.";
		}
		return (ex.getMessage() != null) ? ex.getMessage() : "An unexpected error occurred.";
	}

	private static void triggerToast(ServerWebExchange exchange, String level, String message) {
		String payload = "{\"showToast\":{\"level\":\"" + level + "\",\"message\":\"" + escapeJson(message) + "\"}}";
		exchange.getResponse().getHeaders().set("HX-Trigger", payload);
	}

	private static String escapeJson(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
	}

	/**
	 * View model for a single predicate or filter row in the form, carrying its unique
	 * row index, the selected element name and the ordered map of argument name to value.
	 *
	 * @param index the unique row index used to name the form fields
	 * @param name the selected predicate or filter name, or {@code null} when none
	 * @param args the ordered argument name to value map
	 */
	public record ElementRowView(long index, String name, Map<String, String> args) {

	}

	/**
	 * Mutable accumulator used while parsing the flat form fields back into per-row name
	 * and argument values before they are exposed as immutable {@link ElementRowView}.
	 */
	private static final class RowAccumulator {

		private String name;

		private final Map<String, String> args = new LinkedHashMap<>();

	}

}
