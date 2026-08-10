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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion.VersionFlag;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;

import org.springframework.http.HttpMethod;

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

	private final List<PathTemplate> pathTemplates;

	private final JsonSchemaFactory schemaFactory;

	private final SchemaValidatorsConfig schemaConfig;

	private final ObjectMapper schemaMapper;

	private final JsonNode componentsNode;

	private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

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
		this.schemaFactory = JsonSchemaFactory.getInstance(openapi31 ? VersionFlag.V202012 : VersionFlag.V4);
		// 'nullable' is an OpenAPI 3.0 keyword, not a JSON Schema one: without this a
		// null
		// value would be rejected by a schema that explicitly allows it. In 3.1 the same
		// intent is written as a 'null' type, which needs no special handling.
		this.schemaConfig = SchemaValidatorsConfig.builder().nullableKeywordEnabled(!openapi31).build();
		this.componentsNode = (document.getComponents() != null)
				? this.schemaMapper.valueToTree(document.getComponents()) : null;
		this.pathTemplates = compile(document);
	}

	/**
	 * Resolves the contract operation that should handle the given request.
	 * @param path the request path, relative to the paths the contract declares
	 * @param method the request method
	 * @return what the contract has to say about that path and method
	 */
	public Resolution resolve(String path, HttpMethod method) {
		for (PathTemplate template : this.pathTemplates) {
			Optional<Map<String, String>> variables = template.match(path);
			if (variables.isEmpty()) {
				continue;
			}
			PathItem pathItem = template.pathItem();
			Map<PathItem.HttpMethod, Operation> operations = pathItem.readOperationsMap();
			PathItem.HttpMethod contractMethod = contractMethod(method);
			Operation operation = (contractMethod != null) ? operations.get(contractMethod) : null;
			if (operation == null) {
				Set<String> allowed = new LinkedHashSet<>();
				operations.keySet().forEach((allowedMethod) -> allowed.add(allowedMethod.name()));
				return new Resolution.MethodNotAllowed(template.template(), allowed);
			}
			return new Resolution.Found(template.template(), operation, parameters(pathItem, operation),
					variables.get());
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
	public JsonSchema compiledSchema(String cacheKey, Schema<?> schema) {
		return this.schemaCache.computeIfAbsent(cacheKey, (key) -> compile(schema));
	}

	private JsonSchema compile(Schema<?> schema) {
		ObjectNode node = this.schemaMapper.valueToTree(schema);
		if (this.componentsNode != null) {
			// A resolved document still holds internal '#/components/schemas/...'
			// references: carrying the components inside the schema node is what lets the
			// validator follow them, and it keeps a self-referential schema finite.
			node.set("components", this.componentsNode);
		}
		return this.schemaFactory.getSchema(node, this.schemaConfig);
	}

	private static List<PathTemplate> compile(OpenAPI document) {
		if (document.getPaths() == null) {
			return List.of();
		}
		List<PathTemplate> templates = new ArrayList<>();
		document.getPaths().forEach((template, pathItem) -> templates.add(PathTemplate.of(template, pathItem)));
		// A concrete path takes precedence over a templated one, so that /books/mine is
		// resolved as itself rather than as /books/{id} when the contract declares both.
		templates.sort(Comparator.comparingInt((template) -> template.variableNames().size()));
		return List.copyOf(templates);
	}

	private static boolean isOpenapi31(OpenAPI document) {
		String version = document.getOpenapi();
		return version != null && version.startsWith("3.1");
	}

	private static PathItem.HttpMethod contractMethod(HttpMethod method) {
		for (PathItem.HttpMethod candidate : PathItem.HttpMethod.values()) {
			if (candidate.name().equalsIgnoreCase(method.name())) {
				return candidate;
			}
		}
		return null;
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
	 * A path template of the contract, compiled to a regular expression.
	 */
	private record PathTemplate(String template, PathItem pathItem, Pattern pattern, List<String> variableNames) {

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
			return new PathTemplate(template, pathItem, Pattern.compile(regex.toString()), List.copyOf(names));
		}

		Optional<Map<String, String>> match(String path) {
			Matcher matcher = this.pattern.matcher(path);
			if (!matcher.matches()) {
				return Optional.empty();
			}
			Map<String, String> variables = new LinkedHashMap<>();
			for (int i = 0; i < this.variableNames.size(); i++) {
				variables.put(this.variableNames.get(i), matcher.group(i + 1));
			}
			return Optional.of(variables);
		}

	}

}
