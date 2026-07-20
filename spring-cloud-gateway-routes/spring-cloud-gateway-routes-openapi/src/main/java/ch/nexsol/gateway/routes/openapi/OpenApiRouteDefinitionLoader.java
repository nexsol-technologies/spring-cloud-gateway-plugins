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

import java.util.ArrayList;
import java.util.List;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import io.swagger.v3.oas.models.OpenAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.gateway.route.RouteDefinition;

/**
 * Aggregates the route definitions generated from every configured OpenAPI source.
 */
public class OpenApiRouteDefinitionLoader {

	private static final Logger LOG = LoggerFactory.getLogger(OpenApiRouteDefinitionLoader.class);

	private final OpenApiSpecLoader specLoader;

	private final OpenApiRouteDefinitionMapper mapper;

	private final List<Source> sources;

	/**
	 * Creates the loader.
	 * @param specLoader the OpenAPI document reader
	 * @param mapper the document-to-routes mapper
	 * @param sources the configured sources
	 */
	public OpenApiRouteDefinitionLoader(OpenApiSpecLoader specLoader, OpenApiRouteDefinitionMapper mapper,
			List<Source> sources) {
		this.specLoader = specLoader;
		this.mapper = mapper;
		this.sources = sources;
	}

	/**
	 * Reads every source and generates the corresponding route definitions.
	 * @return the aggregated route definitions
	 */
	public List<RouteDefinition> load() {
		List<RouteDefinition> definitions = new ArrayList<>();
		for (Source source : this.sources) {
			// Isolate failures per source: a broken one must not drop the others' routes.
			try {
				OpenAPI openApi = this.specLoader.load(source.getSpecUrl());
				definitions.addAll(this.mapper.toRouteDefinitions(source, openApi));
			}
			catch (RuntimeException ex) {
				LOG.warn("Skipping OpenAPI source '{}' ({}): {}", source.getId(), source.getSpecUrl(), ex.getMessage());
			}
		}
		LOG.info("Generated {} route definition(s) from {} OpenAPI source(s)", definitions.size(), this.sources.size());
		return definitions;
	}

}
