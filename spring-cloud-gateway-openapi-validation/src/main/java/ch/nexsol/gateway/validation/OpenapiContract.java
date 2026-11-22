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

package ch.nexsol.gateway.validation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.networknt.schema.Error;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriUtils;

/**
 * A parsed OpenAPI document, ready to validate an exchange against.
 * <p>
 * Everything that can be computed once is: the path templates are compiled to regular
 * expressions at construction, and the JSON schema of an operation is compiled the first
 * time it is needed and then kept. Validating a message therefore costs no parsing and no
 * IO, which is what makes it safe to do on the event loop.
 * <p>
 * This class deliberately speaks Jackson 2 rather than the Jackson 3 the rest of the
 * project uses: both {@code swagger-core-jakarta} and {@code json-schema-validator}
 * expose their models through it, so converting would buy nothing.
 */
public class OpenapiContract {

	/**
	 * Keywords holding a value of the instance rather than a schema: what looks like a
	 * reference in there is data.
	 */
	private static final Set<String> DATA_KEYWORDS = Set.of("enum", "const", "default", "example", "examples");

	private static final Set<String> SCHEMAS_BY_NAME_KEYWORDS = Set.of("properties", "patternProperties", "definitions",
			"$defs", "dependentSchemas");

	/**
	 * The paths the contract declares without any variable, by the path they match.
	 */
	private final Map<String, PathTemplate> concretePaths = new LinkedHashMap<>();

	/**
	 * The templated paths, by the number of {@code /} they hold. A variable never spans
	 * one, so a template only ever matches a path holding as many: the others need not be
	 * tried.
	 */
	private final Map<Integer, List<PathTemplate>> templatedPaths = new LinkedHashMap<>();

	private final CompiledSchemas schemas;

	private final ObjectMapper schemaMapper;

	/**
	 * Creates a contract from a parsed document.
	 * @param document the parsed OpenAPI document, with its {@code $ref} resolved
	 */
	public OpenapiContract(OpenAPI document) {
		boolean openapi31 = isOpenapi31(document);
		// The 3.1 mapper renders the 3.1 flavour of the schema keywords (a plain JSON
		// Schema 2020-12), the 3.0 one the older flavour the draft-04 dialect
		// understands.
		this.schemaMapper = openapi31 ? Json31.mapper() : Json.mapper();
		this.schemas = new CompiledSchemas(openapi31, componentsDocument(document));
		compile(document);
	}

	/**
	 * Resolves the contract operation that should handle the given request.
	 * @param path the request path, relative to the paths the contract declares
	 * @param method the request method
	 * @return what the contract has to say about that path and method
	 */
	public Resolution resolve(String path, HttpMethod method) {
		// A concrete path takes precedence over a templated one, so that /books/mine is
		// resolved as itself rather than as /books/{id} when the contract declares both.
		PathTemplate concrete = this.concretePaths.get(path);
		if (concrete != null) {
			return concrete.resolution(method, Map.of());
		}
		for (PathTemplate template : this.templatedPaths.getOrDefault(slashes(path), List.of())) {
			Optional<Map<String, String>> variables = template.match(path);
			if (variables.isPresent()) {
				return template.resolution(method, variables.get());
			}
		}
		return new Resolution.PathNotFound();
	}

	/**
	 * Returns the compiled JSON schema of the given contract schema, compiling it on
	 * first use.
	 * @param cacheKey the key the compiled schema is kept under, unique per operation and
	 * role
	 * @param schema the contract schema
	 * @return the compiled schema
	 */
	/**
	 * Hold an instance against a schema of the contract, compiled once under the given
	 * key.
	 * @param cacheKey the key the compiled schema is kept under, unique to the operation
	 * and the part of the message it describes
	 * @param schema the schema of the contract
	 * @param instance the value built from the message, as a Jackson 3 tree
	 * @return the violations, empty when the instance conforms
	 */
	public List<Error> validate(String cacheKey, Schema<?> schema, JsonNode instance) {
		return this.schemas.validate(cacheKey, () -> schemaJson(schema), instance);
	}

	CompiledSchemas schemas() {
		return this.schemas;
	}

	private String schemaJson(Schema<?> schema) {
		ObjectNode node = this.schemaMapper.valueToTree(schema);
		// A resolved document still holds internal '#/components/schemas/...'
		// references. They are pointed at the one document the components are
		// registered under, rather than carried along as a copy of the whole section
		// inside every schema: that copy is parsed and kept once per parameter, per body
		// and per response.
		pointAtTheComponents(node);
		return node.toString();
	}

	private String componentsDocument(OpenAPI document) {
		if (document.getComponents() == null) {
			return null;
		}
		ObjectNode root = this.schemaMapper.createObjectNode();
		root.set("components", this.schemaMapper.valueToTree(document.getComponents()));
		return root.toString();
	}

	private static void pointAtTheComponents(ObjectNode schema) {
		if (schema.get("$ref") instanceof TextNode reference && reference.textValue().startsWith("#/")) {
			schema.put("$ref", CompiledSchemas.COMPONENTS_DOCUMENT + reference.textValue());
		}
		for (var keyword : schema.properties()) {
			if (DATA_KEYWORDS.contains(keyword.getKey())) {
				continue;
			}
			if (keyword.getValue() instanceof ObjectNode value) {
				if (SCHEMAS_BY_NAME_KEYWORDS.contains(keyword.getKey())) {
					// The keys are names the contract chose, and one of them may read
					// like a keyword: a property called 'default' is a schema like any
					// other.
					value.forEach(OpenapiContract::pointAtTheComponentsIfSchema);
				}
				else {
					pointAtTheComponents(value);
				}
			}
			else if (keyword.getValue() instanceof ArrayNode values) {
				values.forEach(OpenapiContract::pointAtTheComponentsIfSchema);
			}
		}
	}

	private static void pointAtTheComponentsIfSchema(Object node) {
		if (node instanceof ObjectNode schema) {
			pointAtTheComponents(schema);
		}
	}

	private void compile(OpenAPI document) {
		if (document.getPaths() == null) {
			return;
		}
		List<PathTemplate> templates = new ArrayList<>();
		document.getPaths().forEach((template, pathItem) -> templates.add(PathTemplate.of(template, pathItem)));
		// Among the templates matching a path, the one with the fewest variables wins,
		// then the one the contract declares first: the sort is stable.
		templates.sort(Comparator.comparingInt((template) -> template.variableNames().size()));
		for (PathTemplate template : templates) {
			if (template.variableNames().isEmpty()) {
				this.concretePaths.putIfAbsent(template.template(), template);
			}
			else {
				this.templatedPaths.computeIfAbsent(slashes(template.template()), (count) -> new ArrayList<>())
					.add(template);
			}
		}
	}

	private static int slashes(String path) {
		int count = 0;
		for (int index = path.indexOf('/'); index >= 0; index = path.indexOf('/', index + 1)) {
			count++;
		}
		return count;
	}

	private static boolean isOpenapi31(OpenAPI document) {
		String version = document.getOpenapi();
		return version != null && version.startsWith("3.1");
	}

	/**
	 * Merges the parameters declared on the path item with those declared on the
	 * operation. A parameter declared on both, for the same name and location, is taken
	 * from the operation, as the specification requires.
	 */
	private static List<Parameter> parameters(PathItem pathItem, Operation operation) {
		Map<String, Parameter> merged = new LinkedHashMap<>();
		collect(pathItem.getParameters(), merged);
		collect(operation.getParameters(), merged);
		return List.copyOf(merged.values());
	}

	private static void collect(List<Parameter> parameters, Map<String, Parameter> merged) {
		if (parameters == null) {
			return;
		}
		for (Parameter parameter : parameters) {
			if (parameter.getName() != null && parameter.getIn() != null) {
				merged.put(parameter.getIn() + ":" + parameter.getName(), parameter);
			}
		}
	}

	/**
	 * What the contract has to say about a request path and method.
	 */
	public sealed interface Resolution {

		/**
		 * The contract declares no path matching the request.
		 */
		record PathNotFound() implements Resolution {

		}

		/**
		 * The contract declares the path but not for this method.
		 *
		 * @param template the path template that matched
		 * @param allowedMethods the methods the contract declares for that path
		 */
		record MethodNotAllowed(String template, Set<String> allowedMethods) implements Resolution {

		}

		/**
		 * The contract declares the operation.
		 *
		 * @param template the path template that matched
		 * @param operation the resolved operation
		 * @param parameters the parameters of the operation, merged with those of the
		 * path
		 * @param pathVariables the values the path template captured
		 */
		record Found(String template, Operation operation, List<Parameter> parameters,
				Map<String, String> pathVariables) implements Resolution {

		}

	}

	/**
	 * An operation the contract declares on a path, with its parameters merged with those
	 * of the path.
	 *
	 * @param operation the declared operation
	 * @param parameters its parameters
	 */
	private record Declared(Operation operation, List<Parameter> parameters) {

	}

	/**
	 * A path template of the contract, compiled to a regular expression, with what the
	 * contract declares on it by method.
	 */
	private record PathTemplate(String template, Pattern pattern, List<String> variableNames,
			Map<String, Declared> operations, Set<String> allowedMethods) {

		private static final Pattern VARIABLE = Pattern.compile("\\{([^/}]+)}");

		static PathTemplate of(String template, PathItem pathItem) {
			StringBuilder regex = new StringBuilder("^");
			List<String> names = new ArrayList<>();
			Matcher matcher = VARIABLE.matcher(template);
			int literalStart = 0;
			while (matcher.find()) {
				regex.append(Pattern.quote(template.substring(literalStart, matcher.start())));
				// A path variable spans one segment: it must never swallow a '/', or
				// /books/{id} would match /books/1/reviews.
				regex.append("([^/]+)");
				names.add(matcher.group(1));
				literalStart = matcher.end();
			}
			regex.append(Pattern.quote(template.substring(literalStart))).append("$");
			// Read once, here: the model builds a new map of its operations on every
			// call, and merging the parameters is the same work for every request.
			Map<String, Declared> operations = new LinkedHashMap<>();
			pathItem.readOperationsMap()
				.forEach((method, operation) -> operations.put(method.name(),
						new Declared(operation, parameters(pathItem, operation))));
			return new PathTemplate(template, Pattern.compile(regex.toString()), List.copyOf(names), operations,
					Collections.unmodifiableSet(operations.keySet()));
		}

		Resolution resolution(HttpMethod method, Map<String, String> variables) {
			Declared declared = this.operations.get(method.name().toUpperCase(Locale.ROOT));
			if (declared == null) {
				return new Resolution.MethodNotAllowed(this.template, this.allowedMethods);
			}
			return new Resolution.Found(this.template, declared.operation(), declared.parameters(), variables);
		}

		Optional<Map<String, String>> match(String path) {
			Matcher matcher = this.pattern.matcher(path);
			if (!matcher.matches()) {
				return Optional.empty();
			}
			Map<String, String> variables = new LinkedHashMap<>();
			for (int i = 0; i < this.variableNames.size(); i++) {
				variables.put(this.variableNames.get(i), decode(matcher.group(i + 1)));
			}
			return Optional.of(variables);
		}

		/*
		 * The template is matched against the path as it travels, so that an encoded
		 * slash stays inside the variable it was sent in; the value is then decoded,
		 * because the upstream service reads it decoded. Held against its schema still
		 * encoded, %2e%2e satisfies a pattern forbidding dots and arrives as '..'.
		 */
		private static String decode(String value) {
			try {
				return UriUtils.decode(value, StandardCharsets.UTF_8);
			}
			catch (IllegalArgumentException ex) {
				return value;
			}
		}

	}

}
