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

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.PathItem;

/**
 * One operation of a downstream contract, as the gateway serves it: where a call goes and
 * how the arguments of a tool call map onto the request.
 *
 * @param service the service the operation belongs to
 * @param method the HTTP method
 * @param urlTemplate the URL through the gateway, with the path parameters as
 * {@code {name}} placeholders — a template, not a URI, until they are filled in
 * @param parameters the parameters of the operation, by name
 * @param bodyArgument the argument carrying the request body, {@code null} when the
 * operation takes none
 */
public record ApiOperation(String service, PathItem.HttpMethod method, String urlTemplate,
		Map<String, Location> parameters, String bodyArgument) {

	/**
	 * Where a parameter is written on the request.
	 */
	public enum Location {

		PATH, QUERY, HEADER, COOKIE

	}

	/**
	 * The names of the parameters written at one location.
	 * @param location the location
	 * @return the parameter names, in declaration order
	 */
	public List<String> parametersAt(Location location) {
		return this.parameters.entrySet()
			.stream()
			.filter((entry) -> entry.getValue() == location)
			.map(Map.Entry::getKey)
			.toList();
	}

}
