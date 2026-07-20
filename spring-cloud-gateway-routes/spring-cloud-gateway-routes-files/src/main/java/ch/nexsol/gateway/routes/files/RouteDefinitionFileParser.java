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

package ch.nexsol.gateway.routes.files;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

/**
 * Parses a JSON or YAML file into a list of Spring Cloud Gateway {@link RouteDefinition}.
 * <p>
 * The accepted structure mirrors the standard {@code spring.cloud.gateway.routes}
 * configuration. A file may either be a top-level array of routes or an object with a
 * {@code routes} array. Each predicate and filter can be expressed either as the
 * shorthand string form ({@code Cookie=mycookie,mycookievalue}), which is parsed by the
 * native {@link PredicateDefinition}/{@link FilterDefinition} string constructors, or as
 * an object with {@code name} and {@code args}.
 */
public class RouteDefinitionFileParser {

	private final ObjectMapper jsonMapper = new JsonMapper();

	private final ObjectMapper yamlMapper = new YAMLMapper();

	/**
	 * Parses the content of a single route file.
	 * @param filename the file name, used to select the format and for error messages
	 * @param input the file content stream
	 * @return the parsed route definitions
	 */
	public List<RouteDefinition> parse(String filename, InputStream input) {
		ObjectMapper mapper = isYaml(filename) ? this.yamlMapper : this.jsonMapper;
		JsonNode root;
		try {
			root = mapper.readTree(input);
		}
		catch (JacksonException ex) {
			throw new RouteDefinitionFileException("Unable to parse routes file '" + filename + "'", ex);
		}
		JsonNode routesNode = resolveRoutesNode(root);
		if (routesNode == null || !routesNode.isArray()) {
			throw new RouteDefinitionFileException(
					"Routes file '" + filename + "' must be an array or an object with a 'routes' array");
		}
		List<RouteDefinition> definitions = new ArrayList<>();
		for (JsonNode routeNode : routesNode) {
			definitions.add(toRouteDefinition(mapper, filename, routeNode));
		}
		return definitions;
	}

	private JsonNode resolveRoutesNode(JsonNode root) {
		if (root == null) {
			return null;
		}
		return root.isArray() ? root : root.get("routes");
	}

	private RouteDefinition toRouteDefinition(ObjectMapper mapper, String filename, JsonNode node) {
		RouteDefinition definition = new RouteDefinition();
		JsonNode idNode = node.get("id");
		if (idNode != null && idNode.isString()) {
			definition.setId(idNode.stringValue());
		}
		JsonNode uriNode = node.get("uri");
		if (uriNode == null || !uriNode.isString()) {
			throw new RouteDefinitionFileException("A route in '" + filename + "' is missing a 'uri'");
		}
		definition.setUri(URI.create(uriNode.stringValue()));
		JsonNode orderNode = node.get("order");
		if (orderNode != null && orderNode.isNumber()) {
			definition.setOrder(orderNode.asInt());
		}
		definition.setPredicates(parsePredicates(node.get("predicates")));
		definition.setFilters(parseFilters(node.get("filters")));
		JsonNode metadataNode = node.get("metadata");
		if (metadataNode != null && metadataNode.isObject()) {
			definition.setMetadata(mapper.treeToValue(metadataNode, Map.class));
		}
		return definition;
	}

	private List<PredicateDefinition> parsePredicates(JsonNode node) {
		List<PredicateDefinition> predicates = new ArrayList<>();
		if (node == null || !node.isArray()) {
			return predicates;
		}
		for (JsonNode element : node) {
			if (element.isString()) {
				predicates.add(new PredicateDefinition(element.stringValue()));
			}
			else if (element.isObject()) {
				PredicateDefinition predicate = new PredicateDefinition();
				predicate.setName(requiredName(element, "predicate"));
				predicate.setArgs(readArgs(element.get("args")));
				predicates.add(predicate);
			}
		}
		return predicates;
	}

	private List<FilterDefinition> parseFilters(JsonNode node) {
		List<FilterDefinition> filters = new ArrayList<>();
		if (node == null || !node.isArray()) {
			return filters;
		}
		for (JsonNode element : node) {
			if (element.isString()) {
				filters.add(new FilterDefinition(element.stringValue()));
			}
			else if (element.isObject()) {
				FilterDefinition filter = new FilterDefinition();
				filter.setName(requiredName(element, "filter"));
				filter.setArgs(readArgs(element.get("args")));
				filters.add(filter);
			}
		}
		return filters;
	}

	private String requiredName(JsonNode element, String kind) {
		JsonNode nameNode = element.get("name");
		if (nameNode == null || !nameNode.isString()) {
			throw new RouteDefinitionFileException("A " + kind + " object is missing a 'name'");
		}
		return nameNode.stringValue();
	}

	private Map<String, String> readArgs(JsonNode argsNode) {
		Map<String, String> args = new LinkedHashMap<>();
		if (argsNode != null && argsNode.isObject()) {
			for (Map.Entry<String, JsonNode> entry : argsNode.properties()) {
				args.put(entry.getKey(), entry.getValue().asString());
			}
		}
		return args;
	}

	private boolean isYaml(String filename) {
		if (filename == null) {
			return false;
		}
		String lower = filename.toLowerCase();
		return lower.endsWith(".yml") || lower.endsWith(".yaml");
	}

}
