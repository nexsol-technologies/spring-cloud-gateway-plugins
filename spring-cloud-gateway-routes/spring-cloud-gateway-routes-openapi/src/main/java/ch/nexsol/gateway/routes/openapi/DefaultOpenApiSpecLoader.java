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

package ch.nexsol.gateway.routes.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import org.springframework.util.StringUtils;

/**
 * {@link OpenApiSpecLoader} backed by {@link OpenAPIV3Parser}, resolving {@code $ref}
 * references so that operations and paths are fully materialised.
 */
public class DefaultOpenApiSpecLoader implements OpenApiSpecLoader {

	@Override
	public OpenAPI load(String specUrl) {
		if (!StringUtils.hasText(specUrl)) {
			throw new OpenApiRouteException("An OpenAPI source is missing its 'spec-url'");
		}
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		SwaggerParseResult result = new OpenAPIV3Parser().readLocation(specUrl, null, options);
		if (result == null || result.getOpenAPI() == null) {
			String messages = (result != null) ? String.valueOf(result.getMessages()) : "no result";
			throw new OpenApiRouteException("Unable to parse OpenAPI document at '" + specUrl + "': " + messages);
		}
		return result.getOpenAPI();
	}

}
