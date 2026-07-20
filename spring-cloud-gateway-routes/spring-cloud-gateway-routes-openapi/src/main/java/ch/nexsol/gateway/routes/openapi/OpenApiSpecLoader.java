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

/**
 * Reads an OpenAPI document from a location into the Swagger object model.
 * Implementations may perform blocking IO and are expected to be invoked off the request
 * path.
 */
public interface OpenApiSpecLoader {

	/**
	 * Loads and parses the OpenAPI document at the given location.
	 * @param specUrl the document location (an {@code http(s)} URL or a file path)
	 * @return the parsed document
	 * @throws OpenApiRouteException if the document cannot be read or parsed
	 */
	OpenAPI load(String specUrl);

}
