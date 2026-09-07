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

package ch.nexsol.gateway.ui.insights;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.ui.insights.ActuatorClient.EndpointUnavailable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/**
 * Serves the introspection views: what this gateway is made of, read from its own
 * Actuator endpoints.
 * <p>
 * Every view is the same page with a different endpoint behind it, so they share one
 * template and one script and differ only by the view id in the URL. Each is served with
 * the list of instances it can be pointed at, which is what lets a console fronting a
 * cluster read one instance rather than whichever answered.
 */
@Controller
@RequestMapping("/ui/insights")
public class InsightsController {

	/**
	 * The views, in the order they appear in the menu: the view id, the Actuator endpoint
	 * behind it, the heading and the sentence under it.
	 */
	static final Map<String, View> VIEWS = views();

	private final ActuatorClient client;

	private final ActuatorInstances instances;

	private final LoggerWriteAccess writeAccess;

	private final LoggerBaseline baseline;

	/**
	 * Creates the controller.
	 * @param client the client reading the Actuator endpoints
	 * @param instances the instances a view can be pointed at
	 * @param writeAccess whether the principal behind a request may change a level
	 * @param baseline the levels each instance was first read with
	 */
	public InsightsController(ActuatorClient client, ActuatorInstances instances, LoggerWriteAccess writeAccess,
			LoggerBaseline baseline) {
		this.client = client;
		this.instances = instances;
		this.writeAccess = writeAccess;
		this.baseline = baseline;
	}

	/**
	 * Renders one introspection view inside the shell.
	 * @param view the view id, e.g. {@code loggers}
	 * @param model the view model
	 * @return the page view name
	 */
	@GetMapping("/{view}")
	public Mono<String> page(@PathVariable String view, Model model, ServerWebExchange exchange) {
		View declared = VIEWS.get(view);
		if (declared == null) {
			return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
		}
		model.addAttribute("activeNav", "insights-" + view);
		model.addAttribute("view", declared);
		model.addAttribute("viewId", view);
		// Only ever what to draw. The write itself is checked again when it arrives: a
		// page
		// rendered with the control is not permission to use it.
		Mono<Boolean> writable = "loggers".equals(view) ? this.writeAccess.granted(exchange) : Mono.just(false);
		return writable.doOnNext((allowed) -> model.addAttribute("writable", allowed))
			.then(this.instances.list())
			.doOnNext((known) -> model.addAttribute("instances", known))
			.thenReturn("dashboard/insights");
	}

	/**
	 * Returns the payload one view draws, read from the instance it named.
	 * @param view the view id
	 * @param instanceId the instance to read, this one when absent
	 * @param exchange the request being served
	 * @return the Actuator payload, or the reason it could not be read
	 */
	@GetMapping("/{view}/data")
	@ResponseBody
	public Mono<ResponseEntity<Map<String, Object>>> data(@PathVariable String view,
			@RequestParam(name = "instance", required = false) String instanceId, ServerWebExchange exchange) {
		View declared = VIEWS.get(view);
		if (declared == null) {
			return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
		}
		Mono<ResponseEntity<Map<String, Object>>> answer = everyInstance(view, instanceId)
				? readAll(exchange, declared, view) : readOne(exchange, declared, view, instanceId);
		return answer.onErrorResume(EndpointUnavailable.class, (ex) -> Mono.just(ResponseEntity
			.status(HttpStatus.BAD_GATEWAY)
			.body(Map.of("unavailable", true, "endpoint", ex.endpoint(), "reason", String.valueOf(ex.getMessage())))));
	}

	private Mono<ResponseEntity<Map<String, Object>>> readOne(ServerWebExchange exchange, View declared, String view,
			String instanceId) {
		return this.instances.resolve(instanceId)
			.flatMap((instance) -> this.client.read(exchange, instance, declared.endpoint())
				.map((payload) -> ResponseEntity.ok(answered(view, payload, instance.id()))));
	}

	/**
	 * Reads every instance and reports what they agree on.
	 * <p>
	 * An instance that cannot be read is left out rather than failing the view: the fleet
	 * is still worth showing without the one that is down, and the payload names the ones
	 * that answered so the page can say what it is showing. Only when none answers does
	 * this report the failure.
	 */
	private Mono<ResponseEntity<Map<String, Object>>> readAll(ServerWebExchange exchange, View declared, String view) {
		return this.instances.list()
			.flatMap((known) -> Flux.fromIterable(known)
				.concatMap((instance) -> this.client.read(exchange, instance, declared.endpoint())
					.map((payload) -> Map.entry(instance.id(), payload))
					.onErrorResume((ex) -> Mono.empty()))
				.collectList()
				.flatMap((answers) -> {
					if (answers.isEmpty()) {
						return Mono.error(new EndpointUnavailable(declared.endpoint(), "no instance answered"));
					}
					Map<String, Map<String, Object>> byInstance = new LinkedHashMap<>();
					answers.forEach((entry) -> byInstance.put(entry.getKey(), entry.getValue()));
					return Mono
						.just(ResponseEntity.ok(answered(view, MergedLoggers.of(byInstance), ActuatorInstances.ALL)));
				}));
	}

	/**
	 * Whether a request means the whole fleet. The loggers view is the one that writes,
	 * so it is the one that defaults to every instance: a level raised on the instance
	 * that happened to answer leaves the others recording nothing.
	 */
	private static boolean everyInstance(String view, String instanceId) {
		return "loggers".equals(view) && (instanceId == null || ActuatorInstances.ALL.equals(instanceId));
	}

	/**
	 * Sets the level of one logger on one instance, or on all of them.
	 * <p>
	 * The only write any introspection view makes, and it is off unless the operator
	 * turned it on: it changes what a running gateway records, on an instance the reader
	 * picked.
	 * @param name the logger to set
	 * @param instanceId the instance to write to, this one when absent
	 * @param body the level to set, as Actuator expects it
	 * @param exchange the request being served
	 * @return a mono completing once the level is set
	 */
	@PostMapping("/loggers/{name}")
	@ResponseBody
	public Mono<ResponseEntity<Void>> setLevel(@PathVariable String name,
			@RequestParam(name = "instance", required = false) String instanceId, @RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		return this.writeAccess.granted(exchange).flatMap((allowed) -> {
			if (!allowed) {
				return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
						"Changing a level asks for an authenticated principal holding the configured role"));
			}
			Mono<Void> written = everyInstance("loggers", instanceId) ? writeAll(exchange, name, body)
					: this.instances.resolve(instanceId)
						.flatMap((instance) -> this.client.write(exchange, instance, "loggers/" + name, body));
			return written.thenReturn(ResponseEntity.noContent().<Void>build())
				.onErrorResume(EndpointUnavailable.class,
						(ex) -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()));
		});
	}

	/**
	 * Writes the level to every instance.
	 * <p>
	 * All of them are attempted before any failure is raised, so one unreachable instance
	 * does not leave the rest of the fleet unset: the write that reached is worth
	 * keeping, and the page reports the failure once the others are done.
	 */
	private Mono<Void> writeAll(ServerWebExchange exchange, String name, Map<String, Object> body) {
		return this.instances.list()
			.flatMapMany(Flux::fromIterable)
			.concatMapDelayError((instance) -> this.client.write(exchange, instance, "loggers/" + name, body))
			.then();
	}

	/**
	 * Echoes back which instance answered, so the page can say what it is showing even
	 * when the instance it asked for has gone away since, and — for the loggers view —
	 * the levels that instance was first read with.
	 */
	private Map<String, Object> answered(String view, Map<String, Object> payload, String instanceId) {
		Map<String, Object> answered = new LinkedHashMap<>(payload);
		answered.put("_instance", instanceId);
		// The loggers view offers to put a level back to the one the application
		// declared,
		// which only this console remembers: Actuator has no notion of an original level.
		if ("loggers".equals(view)) {
			answered.put("_baseline", this.baseline.of(instanceId, payload));
		}
		return answered;
	}

	private static Map<String, View> views() {
		Map<String, View> views = new LinkedHashMap<>();
		views.put("configuration", new View("Configuration", "configprops",
				"The configuration properties this gateway bound, by prefix.", 40, "icon-sliders"));
		views.put("profile-diff", new View("Profile Diff", "env",
				"Which property source a value came from, and what the others said.", 41, "icon-layers"));
		views.put("loggers", new View("Loggers", "loggers",
				"The loggers of this gateway and the level each one records at.", 42, "icon-journal"));
		views.put("beans", new View("Beans", "beans",
				"Every bean in the context, what it is, and what it was wired from.", 43, "icon-hierarchy"));
		views.put("conditions", new View("Conditions", "conditions",
				"Why each auto-configuration was applied, or was not.", 44, "icon-check"));
		views.put("mappings", new View("Mappings", "mappings", "The routes and handlers this gateway answers on.", 45,
				"icon-signpost"));
		// Insertion-ordered on purpose: this is the order of the menu. Map.copyOf would
		// hand back a map that iterates in whatever order it likes.
		return Collections.unmodifiableMap(views);
	}

	/**
	 * One declared view, by the id it is served under.
	 * @param id the view id, e.g. {@code loggers}
	 * @return the view, {@code null} when no view is served under that id
	 */
	public static View view(String id) {
		return VIEWS.get(id);
	}

	/**
	 * @return the views, for the auto-configuration contributing their menu entries
	 */
	public static List<Map.Entry<String, View>> declared() {
		return List.copyOf(VIEWS.entrySet());
	}

	/**
	 * One introspection view.
	 *
	 * @param label the heading, and the label of its menu entry
	 * @param endpoint the Actuator endpoint behind it
	 * @param description the sentence under the heading
	 * @param order where its menu entry sits
	 * @param icon the id of the SVG symbol its menu entry is drawn with
	 */
	public record View(String label, String endpoint, String description, int order, String icon) {

	}

}
