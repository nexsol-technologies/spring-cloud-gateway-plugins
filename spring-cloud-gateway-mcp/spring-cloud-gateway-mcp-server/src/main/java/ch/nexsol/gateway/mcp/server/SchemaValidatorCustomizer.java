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

package ch.nexsol.gateway.mcp.server;

import java.util.function.Supplier;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.mcp.customizer.McpStatelessAsyncServerCustomizer;

/**
 * Hands the server a JSON schema validator it can load. The SDK's own validator is built
 * on {@code com.networknt:json-schema-validator} 3.x, which an application also carrying
 * {@code spring-cloud-gateway-openapi-validation} does not have: that plugin needs the
 * 1.5 line, and Maven keeps the nearer of the two. Left to itself the server then fails
 * at start-up on a {@link NoClassDefFoundError}. Here the SDK's validator is used
 * whenever it loads, and otherwise replaced by one that accepts every schema and every
 * argument: the tools still answer, and a wrongly typed argument is reported by the tool
 * itself rather than refused before it runs.
 */
public class SchemaValidatorCustomizer implements McpStatelessAsyncServerCustomizer {

	private static final Logger LOG = LoggerFactory.getLogger(SchemaValidatorCustomizer.class);

	@Override
	public void customize(McpServer.StatelessAsyncSpecification serverBuilder) {
		serverBuilder.jsonSchemaValidator(validator(McpJsonDefaults::getSchemaValidator));
	}

	static JsonSchemaValidator validator(Supplier<JsonSchemaValidator> sdkDefault) {
		try {
			return sdkDefault.get();
		}
		catch (LinkageError ex) {
			LOG.warn("The JSON schema validator of the MCP SDK cannot load ({}): json-schema-validator 3.x is "
					+ "shadowed by an older version on the classpath. Tool arguments are not checked against "
					+ "their schema before a tool runs.", ex.toString());
			return (schema, structuredContent) -> JsonSchemaValidator.ValidationResponse.asValid(null);
		}
	}

}
