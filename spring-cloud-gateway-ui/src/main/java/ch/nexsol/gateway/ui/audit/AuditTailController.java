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

package ch.nexsol.gateway.ui.audit;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the audit view: the tail of the exchanges the audit plugin captured, as a live
 * list. The full page renders inside the shell, and the {@code /events} endpoint feeds it
 * with the buffered events as JSON.
 */
@Controller
@RequestMapping("/ui/audit")
public class AuditTailController {

	private final AuditTailBuffer buffer;

	/**
	 * Creates the controller over the in-memory event tail.
	 * @param buffer the buffer holding the recent audit events
	 */
	public AuditTailController(AuditTailBuffer buffer) {
		this.buffer = buffer;
	}

	/**
	 * Renders the audit page inside the shell.
	 * @param model the view model
	 * @return the page view name
	 */
	@GetMapping
	public String page(Model model) {
		model.addAttribute("activeNav", "audit");
		model.addAttribute("capacity", AuditTailBuffer.CAPACITY);
		return "dashboard/audit";
	}

	/**
	 * Returns the buffered events, newest first, narrowed by the requested filters.
	 * @param status the status class to keep, e.g. {@code 5xx}, or blank for all
	 * @param query a case-insensitive fragment matched against method, path, user, ip and
	 * trace id, or blank for all
	 * @param limit the maximum number of events to return
	 * @return the matching events
	 */
	@GetMapping("/events")
	@ResponseBody
	public List<AuditEventView> events(@RequestParam(required = false) String status,
			@RequestParam(required = false) String query, @RequestParam(defaultValue = "100") int limit) {
		String needle = StringUtils.hasText(query) ? query.trim().toLowerCase(Locale.ROOT) : null;
		return this.buffer.snapshot()
			.stream()
			.filter((event) -> matchesStatus(event, status))
			.filter((event) -> matchesQuery(event, needle))
			.limit(Math.clamp(limit, 1, AuditTailBuffer.CAPACITY))
			.toList();
	}

	/**
	 * Keeps the events of the requested status class, e.g. {@code 5xx}. Events whose
	 * status could not be resolved are dropped as soon as a class is requested.
	 * @param event the event to test
	 * @param status the requested status class, or blank for all
	 * @return whether the event belongs to the requested class
	 */
	static boolean matchesStatus(AuditEventView event, String status) {
		if (!StringUtils.hasText(status)) {
			return true;
		}
		char requested = status.charAt(0);
		return Character.isDigit(requested) && event.statusCode() / 100 == Character.getNumericValue(requested);
	}

	/**
	 * Keeps the events carrying the given fragment in one of the fields the view shows.
	 * @param event the event to test
	 * @param needle the lower-cased fragment to look for, or {@code null} for all
	 * @return whether the event matches
	 */
	static boolean matchesQuery(AuditEventView event, String needle) {
		if (needle == null) {
			return true;
		}
		return contains(event.method(), needle) || contains(event.path(), needle) || contains(event.user(), needle)
				|| contains(event.ip(), needle) || contains(event.traceId(), needle);
	}

	private static boolean contains(String value, String needle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
	}

}
