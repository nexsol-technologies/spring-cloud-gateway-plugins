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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

import org.springframework.core.io.ClassPathResource;

/**
 * The contract the tests of this module share: two paths, four operations, a body schema
 * reached through a reference, and the OpenAPI 3.0 keywords a JSON Schema client must not
 * see.
 */
final class Contracts {

	static final String PETSTORE = read();

	private Contracts() {
	}

	private static String read() {
		try {
			return new ClassPathResource("openapi/petstore.yaml").getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static OpenAPI petstore() {
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		return new OpenAPIV3Parser().readContents(PETSTORE, null, options).getOpenAPI();
	}

}
