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

package ch.nexsol.gateway.mcp.hub;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;

import org.springframework.util.StringUtils;

/**
 * Turns the operations of a contract into MCP tools: one tool per operation, named after
 * the service and the operation, taking the parameters and the body of the operation as
 * its arguments.
 * <p>
 * The input schema is rebuilt from the contract rather than copied: a contract carries
 * OpenAPI 3.0 keywords ({@code nullable}, a boolean {@code exclusiveMinimum}) and
 * annotations that are not JSON Schema 2020-12, and the server refuses a tool whose
 * schema is not. Only the keywords a client needs to build a call are kept.
 */
public final class OperationTools {

	static final String BODY = "body";

	static final String CONFIRM = "confirm";

	static final int MAX_NAME_LENGTH = 64;

	private static final int MAX_DEPTH = 8;

	private static final Pattern NOT_A_NAME_CHARACTER = Pattern.compile("[^a-z0-9_-]+");

	private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/}]+}");

	private static final Set<String> KEPT = Set.of("type", "description", "enum", "format", "default", "minimum",
			"maximum", "minLength", "maxLength", "pattern", "minItems", "maxItems");

	private OperationTools() {
	}

	/**
	 * The tools of a contract.
	 * @param service the service the contract documents, which prefixes every tool name
	 * @param contract the contract, references resolved
	 * @param base the URL the operations are served under through the gateway
	 * @param writeEnabled whether the operations that change state become tools too
	 * @param confirmWrites whether those take a {@code confirm} argument the call must
	 * carry
	 * @return the tools, in contract order
	 */
	public static List<ApiTool> of(String service, OpenAPI contract, URI base, boolean writeEnabled,
			boolean confirmWrites) {
		List<ApiTool> tools = new ArrayList<>();
		if (contract.getPaths() == null) {
			return tools;
		}
		Set<String> names = new HashSet<>();
		contract.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
			if (writeEnabled || isReadOnly(method)) {
				tools.add(tool(service, method, path, operation, base, names, confirmWrites));
			}
		}));
		return tools;
	}

	private static ApiTool tool(String service, PathItem.HttpMethod method, String path, Operation operation, URI base,
			Set<String> taken, boolean confirmWrites) {
		Map<String, Object> properties = new LinkedHashMap<>();
		List<String> required = new ArrayList<>();
		Map<String, ApiOperation.Location> locations = new LinkedHashMap<>();
		for (Parameter parameter : (operation.getParameters() != null) ? operation.getParameters()
				: List.<Parameter>of()) {
			ApiOperation.Location location = location(parameter.getIn());
			if (location == null || !StringUtils.hasText(parameter.getName())) {
				continue;
			}
			locations.put(parameter.getName(), location);
			properties.put(parameter.getName(), schema(parameter.getSchema(), parameter.getDescription(), 0));
			if (Boolean.TRUE.equals(parameter.getRequired()) || location == ApiOperation.Location.PATH) {
				required.add(parameter.getName());
			}
		}
		String bodyArgument = null;
		Schema<?> bodySchema = jsonBody(operation.getRequestBody());
		if (bodySchema != null) {
			bodyArgument = locations.containsKey(BODY) ? "request_" + BODY : BODY;
			properties.put(bodyArgument, schema(bodySchema, operation.getRequestBody().getDescription(), 0));
			if (Boolean.TRUE.equals(operation.getRequestBody().getRequired())) {
				required.add(bodyArgument);
			}
		}
		if (confirmWrites && !isReadOnly(method)) {
			properties.put(CONFIRM, Map.of("type", "boolean", "description",
					"true to make the call; without it the call is described and not made"));
		}
		Map<String, Object> inputSchema = new LinkedHashMap<>();
		inputSchema.put("type", "object");
		inputSchema.put("properties", properties);
		if (!required.isEmpty()) {
			inputSchema.put("required", required);
		}
		String name = uniqueName(service, method, path, operation, taken);
		Tool tool = Tool.builder(name)
			.title(operation.getSummary())
			.description(describe(service, method, path, operation))
			.inputSchema(inputSchema)
			.annotations(ToolAnnotations.builder()
				.readOnlyHint(isReadOnly(method))
				.destructiveHint(method == PathItem.HttpMethod.DELETE)
				.idempotentHint(
						isReadOnly(method) || method == PathItem.HttpMethod.PUT || method == PathItem.HttpMethod.DELETE)
				.openWorldHint(true)
				.build())
			.build();
		return new ApiTool(tool,
				new ApiOperation(service, method, join(base.toString(), path), locations, bodyArgument));
	}

	static boolean isReadOnly(PathItem.HttpMethod method) {
		return method == PathItem.HttpMethod.GET || method == PathItem.HttpMethod.HEAD;
	}

	static String uniqueName(String service, PathItem.HttpMethod method, String path, Operation operation,
			Set<String> taken) {
		String operationName = StringUtils.hasText(operation.getOperationId()) ? snake(operation.getOperationId())
				: snake(method.name() + " " + PATH_VARIABLE.matcher(path).replaceAll("by"));
		String prefix = sanitize(service) + "__";
		String name = prefix + operationName;
		if (name.length() > MAX_NAME_LENGTH) {
			name = name.substring(0, MAX_NAME_LENGTH);
		}
		String candidate = name;
		for (int i = 2; !taken.add(candidate); i++) {
			String suffix = "_" + i;
			candidate = name.substring(0, Math.min(name.length(), MAX_NAME_LENGTH - suffix.length())) + suffix;
		}
		return candidate;
	}

	static String snake(String text) {
		String spaced = text.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
		return sanitize(spaced.replaceAll("[^a-z0-9]+", "_"));
	}

	static String sanitize(String text) {
		String clean = NOT_A_NAME_CHARACTER.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("_");
		clean = clean.replaceAll("^_+|_+$", "");
		return clean.isEmpty() ? "op" : clean;
	}

	private static String describe(String service, PathItem.HttpMethod method, String path, Operation operation) {
		StringBuilder description = new StringBuilder();
		if (StringUtils.hasText(operation.getSummary())) {
			description.append(operation.getSummary().strip()).append(' ');
		}
		if (StringUtils.hasText(operation.getDescription())
				&& !operation.getDescription().strip().equals(operation.getSummary())) {
			description.append(operation.getDescription().strip()).append(' ');
		}
		return description.append('[')
			.append(method.name())
			.append(' ')
			.append(path)
			.append(" of ")
			.append(service)
			.append(']')
			.toString();
	}

	private static ApiOperation.Location location(String in) {
		if (in == null) {
			return ApiOperation.Location.QUERY;
		}
		return switch (in) {
			case "path" -> ApiOperation.Location.PATH;
			case "query" -> ApiOperation.Location.QUERY;
			case "header" -> ApiOperation.Location.HEADER;
			case "cookie" -> ApiOperation.Location.COOKIE;
			default -> null;
		};
	}

	private static Schema<?> jsonBody(RequestBody body) {
		if (body == null || body.getContent() == null) {
			return null;
		}
		for (Map.Entry<String, MediaType> content : body.getContent().entrySet()) {
			if (content.getKey().toLowerCase(Locale.ROOT).contains("json") && content.getValue().getSchema() != null) {
				return content.getValue().getSchema();
			}
		}
		return null;
	}

	/**
	 * The JSON Schema of a contract schema, reduced to what a client needs.
	 * @param schema the contract schema, {@code null} for an untyped value
	 * @param description a description to carry when the schema has none
	 * @param depth how deep in the schema tree this is
	 * @return the JSON Schema
	 */
	@SuppressWarnings("unchecked")
	static Map<String, Object> schema(Schema<?> schema, String description, int depth) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (schema == null || depth > MAX_DEPTH) {
			if (StringUtils.hasText(description)) {
				result.put("description", description);
			}
			return result;
		}
		Map<String, Object> raw = Json.mapper().convertValue(schema, Map.class);
		for (String keyword : KEPT) {
			if (raw.get(keyword) != null) {
				result.put(keyword, plain(raw.get(keyword)));
			}
		}
		if (!result.containsKey("description") && StringUtils.hasText(description)) {
			result.put("description", description);
		}
		if (raw.get("types") instanceof List<?> types && !types.isEmpty()) {
			result.put("type", (types.size() == 1) ? types.get(0) : types);
		}
		if (Boolean.TRUE.equals(raw.get("nullable")) && result.get("type") instanceof String type) {
			result.put("type", List.of(type, "null"));
		}
		if (schema.getItems() != null) {
			result.put("items", schema(schema.getItems(), null, depth + 1));
		}
		if (schema.getProperties() != null) {
			Map<String, Object> properties = new LinkedHashMap<>();
			schema.getProperties().forEach((name, property) -> properties.put(name, schema(property, null, depth + 1)));
			result.put("properties", properties);
			if (schema.getRequired() != null && !schema.getRequired().isEmpty()) {
				result.put("required", List.copyOf(schema.getRequired()));
			}
		}
		return result;
	}

	private static Object plain(Object value) {
		// Swagger reads every bound as a BigDecimal; a client expects the JSON number.
		if (value instanceof BigDecimal decimal) {
			return (decimal.stripTrailingZeros().scale() <= 0) ? (Object) decimal.longValueExact()
					: (Object) decimal.doubleValue();
		}
		return value;
	}

	private static String join(String base, String path) {
		String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
		return left + (path.startsWith("/") ? path : "/" + path);
	}

}
