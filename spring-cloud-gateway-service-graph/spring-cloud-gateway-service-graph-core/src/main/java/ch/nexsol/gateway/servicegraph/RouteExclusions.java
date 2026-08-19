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

package ch.nexsol.gateway.servicegraph;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The route ids left out of the graph, compiled once.
 * <p>
 * Applied where the calls are counted rather than where they are read, so an excluded
 * route never becomes a series at all: what is not counted costs nothing in the registry,
 * in Redis, or in whatever scrapes it.
 */
public class RouteExclusions {

	private static final Logger LOG = LoggerFactory.getLogger(RouteExclusions.class);

	private final List<Pattern> patterns;

	/**
	 * Compile the configured expressions.
	 * <p>
	 * An unusable expression is dropped rather than failing the application: the graph is
	 * a read-only page, and losing a filter is a lesser evil than a gateway that does not
	 * start.
	 * @param expressions the configured regular expressions
	 */
	public RouteExclusions(List<String> expressions) {
		List<Pattern> compiled = new ArrayList<>();
		for (String expression : expressions) {
			try {
				compiled.add(Pattern.compile(expression));
			}
			catch (PatternSyntaxException ex) {
				LOG.warn("Ignoring the service graph route exclusion '{}': {}", expression, ex.getMessage());
			}
		}
		this.patterns = List.copyOf(compiled);
	}

	/**
	 * Whether the given route is left out of the graph. The whole id must match, so
	 * {@code docs} excludes {@code docs} but not {@code docs-public}.
	 * @param routeId the route id, possibly {@code null} or blank
	 * @return {@code true} when the route must not be drawn
	 */
	public boolean excludes(String routeId) {
		if (routeId == null || routeId.isBlank()) {
			return true;
		}
		return this.patterns.stream().anyMatch((pattern) -> pattern.matcher(routeId).matches());
	}

}
