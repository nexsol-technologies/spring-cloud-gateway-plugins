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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.networknt.schema.Error;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.OpenApi30;
import com.networknt.schema.dialect.OpenApi31;
import tools.jackson.databind.JsonNode;

/**
 * The JSON schemas of one contract, compiled once each and held against the instances the
 * validators build from a request or a response.
 * <p>
 * The validator reads Jackson 3 trees, while Swagger renders a schema as a Jackson 2
 * tree: a schema crosses over as JSON text, once, when it is compiled.
 */
final class CompiledSchemas {

	/**
	 * Where the {@code components} of the contract are registered. A schema reaches them
	 * through a reference to this document, never through a copy of its own. The address
	 * is never fetched: the registry holds the document under it.
	 */
	static final String COMPONENTS_DOCUMENT = "https://openapi-contract.invalid/document";

	private final SchemaRegistry registry;

	private final Map<String, Schema> cache = new ConcurrentHashMap<>();

	/**
	 * Creates the schemas of one contract.
	 * @param openapi31 whether the contract is an OpenAPI 3.1 one
	 * @param components the document holding the {@code components} of the contract, as
	 * JSON text, or {@code null} when it declares none
	 */
	CompiledSchemas(boolean openapi31, String components) {
		// The OpenAPI dialects carry what the plain drafts lack. For 3.0 that is the
		// 'nullable' keyword over draft-04: without it a null value is rejected by a
		// schema that explicitly allows it. In 3.1 the same intent is a 'null' type of
		// JSON Schema 2020-12.
		Dialect dialect = openapi31 ? OpenApi31.getInstance() : OpenApi30.getInstance();
		if (components == null) {
			this.registry = SchemaRegistry.withDefaultDialect(dialect);
			return;
		}
		this.registry = SchemaRegistry.withDefaultDialect(dialect,
				(builder) -> builder.schemas(Map.of(COMPONENTS_DOCUMENT, components)));
		// Parsed here, once, by the thread reading the contract: left to the first
		// schema referring to it, the whole section would be parsed on the event loop.
		this.registry.loadSchema(SchemaLocation.of(COMPONENTS_DOCUMENT), false);
	}

	List<Error> validate(String cacheKey, Supplier<String> schemaJson, JsonNode instance) {
		Violations violations = new Violations();
		Consumer<ExecutionContext> collectingIntoThem = (context) -> context.setErrors(violations);
		try {
			compiled(cacheKey, schemaJson).validate(instance, collectingIntoThem);
		}
		catch (Violations.LimitReached ex) {
			// Enough was found: the list is full, and says so.
		}
		return violations;
	}

	Schema compiled(String cacheKey, Supplier<String> schemaJson) {
		return this.cache.computeIfAbsent(cacheKey, (key) -> this.registry.getSchema(schemaJson.get()));
	}

}
