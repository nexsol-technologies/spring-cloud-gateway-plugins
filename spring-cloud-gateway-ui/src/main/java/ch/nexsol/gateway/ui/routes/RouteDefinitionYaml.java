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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.support.NameUtils;

/**
 * Writes route definitions back out as gateway configuration: the YAML a reader can paste
 * into an {@code application.yml} to declare the same routes, under
 * {@code spring.cloud.gateway.server.webflux.routes}.
 * <p>
 * Predicates and filters are written in the shortcut form &mdash; {@code Path=/api/**}
 * &mdash; whenever the gateway would read that back as what was exported, and in the long
 * {@code name}/{@code args} form otherwise. The shortcut splits its value on commas and
 * names its arguments by position, so a named argument or a value carrying a comma has to
 * take the long form or it would come back as a different route.
 */
final class RouteDefinitionYaml {

	private RouteDefinitionYaml() {
	}

	/**
	 * Renders the given routes as a gateway configuration document.
	 * @param routes the definitions to write, in the order they should appear
	 * @return the YAML document
	 */
	static String write(List<RouteDefinition> routes) {
		List<Object> rendered = new ArrayList<>();
		for (RouteDefinition route : routes) {
			rendered.add(route(route));
		}
		return new Yaml(options()).dump(nest(rendered));
	}

	/**
	 * The property path the gateway reads its routes from, rebuilt as nested maps so the
	 * document is the file a reader would have written by hand.
	 */
	private static Map<String, Object> nest(List<Object> routes) {
		Map<String, Object> document = Map.of("routes", routes);
		for (String segment : List.of("webflux", "server", "gateway", "cloud", "spring")) {
			document = Map.of(segment, document);
		}
		return document;
	}

	private static Map<String, Object> route(RouteDefinition route) {
		Map<String, Object> rendered = new LinkedHashMap<>();
		rendered.put("id", route.getId());
		if (route.getUri() != null) {
			rendered.put("uri", route.getUri().toString());
		}
		// Only when it was set: zero is the order of every route that never declared one,
		// and writing it back on all of them buries the handful that did.
		if (route.getOrder() != 0) {
			rendered.put("order", route.getOrder());
		}
		if (!route.getPredicates().isEmpty()) {
			rendered.put("predicates", route.getPredicates().stream().map(RouteDefinitionYaml::predicate).toList());
		}
		if (!route.getFilters().isEmpty()) {
			rendered.put("filters", route.getFilters().stream().map(RouteDefinitionYaml::filter).toList());
		}
		if (route.getMetadata() != null && !route.getMetadata().isEmpty()) {
			rendered.put("metadata", new LinkedHashMap<>(route.getMetadata()));
		}
		return rendered;
	}

	private static Object predicate(PredicateDefinition predicate) {
		return element(predicate.getName(), predicate.getArgs());
	}

	private static Object filter(FilterDefinition filter) {
		return element(filter.getName(), filter.getArgs());
	}

	private static Object element(String name, Map<String, String> args) {
		if (args == null || args.isEmpty()) {
			return name;
		}
		List<String> positional = positional(args);
		if (positional != null) {
			return name + "=" + String.join(",", positional);
		}
		Map<String, Object> rendered = new LinkedHashMap<>();
		rendered.put("name", name);
		rendered.put("args", new LinkedHashMap<String, String>(args));
		return rendered;
	}

	/**
	 * The arguments in shortcut order, or {@code null} when the shortcut form cannot
	 * carry them: an argument the declaration named, or a value holding the comma the
	 * shortcut splits on.
	 */
	private static List<String> positional(Map<String, String> args) {
		List<Map.Entry<String, String>> ordered = new ArrayList<>(args.entrySet());
		for (Map.Entry<String, String> arg : ordered) {
			if (!arg.getKey().startsWith(NameUtils.GENERATED_NAME_PREFIX) || arg.getValue() == null
					|| arg.getValue().indexOf(',') >= 0) {
				return null;
			}
		}
		// '_genkey_10' sorts before '_genkey_2' as text, and the shortcut is read by
		// position: the arguments have to go back in the order they were generated in.
		ordered.sort(Comparator.comparingInt((arg) -> index(arg.getKey())));
		List<String> values = new ArrayList<>(ordered.size());
		for (Map.Entry<String, String> arg : ordered) {
			values.add(arg.getValue());
		}
		return values;
	}

	private static int index(String key) {
		try {
			return Integer.parseInt(key.substring(NameUtils.GENERATED_NAME_PREFIX.length()));
		}
		catch (NumberFormatException ex) {
			return Integer.MAX_VALUE;
		}
	}

	private static DumperOptions options() {
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setIndent(2);
		options.setSplitLines(false);
		return options;
	}

}
