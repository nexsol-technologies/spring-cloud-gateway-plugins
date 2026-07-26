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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.StringUtils;

/**
 * Resolves the OpenAPI sources the routes are generated from: the ones configured inline,
 * plus the ones declared in the documents the {@code sources-locations} point at.
 * <p>
 * A location is anything the resource resolver understands &mdash; {@code classpath:},
 * {@code file:} or an {@code http(s)} URL, so a document served by a Config Server is
 * addressed like any other. Documents are read on every load, so a change is picked up by
 * the next refresh.
 * <p>
 * A document is either an array of sources, or an object with a {@code sources} array,
 * written exactly like the inline configuration. A document that cannot be read or parsed
 * is skipped rather than dropping the sources the others declared, in keeping with the
 * per-source isolation the generator already applies.
 */
public class OpenapiSourcesLoader {

	private static final Logger LOG = LoggerFactory.getLogger(OpenapiSourcesLoader.class);

	private final ObjectMapper jsonMapper = JsonMapper.builder()
		.propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
		.build();

	private final ObjectMapper yamlMapper = YAMLMapper.builder()
		.propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
		.build();

	private final RoutesOpenapiProperties properties;

	private final ResourcePatternResolver resolver;

	/**
	 * Creates the loader.
	 * @param properties the OpenAPI locator properties, holding the inline sources and
	 * the document locations
	 * @param resolver the resolver used to reach the documents
	 */
	public OpenapiSourcesLoader(RoutesOpenapiProperties properties, ResourcePatternResolver resolver) {
		this.properties = properties;
		this.resolver = resolver;
	}

	/**
	 * Returns the configured sources, the inline ones first, then those read from the
	 * documents in the order their locations are declared.
	 * @return the sources the routes are generated from
	 */
	public List<Source> load() {
		List<Source> sources = new ArrayList<>(this.properties.getSources());
		for (String location : this.properties.getSourcesLocations()) {
			if (StringUtils.hasText(location)) {
				sources.addAll(read(location));
			}
		}
		return sources;
	}

	private List<Source> read(String location) {
		List<Source> sources = new ArrayList<>();
		Resource[] resources;
		try {
			resources = this.resolver.getResources(location);
		}
		catch (IOException ex) {
			LOG.warn("Skipping OpenAPI sources location '{}': {}", location, ex.getMessage());
			return sources;
		}
		for (Resource resource : resources) {
			if (!resource.exists()) {
				LOG.warn("Skipping OpenAPI sources document '{}': it does not exist", resource);
				continue;
			}
			try (InputStream input = resource.getInputStream()) {
				sources.addAll(parse(resource.getFilename(), input));
			}
			catch (IOException | JacksonException | IllegalArgumentException ex) {
				LOG.warn("Skipping OpenAPI sources document '{}': {}", resource, ex.getMessage());
			}
		}
		return sources;
	}

	private List<Source> parse(String filename, InputStream input) {
		ObjectMapper mapper = isYaml(filename) ? this.yamlMapper : this.jsonMapper;
		JsonNode sourcesNode = resolveSourcesNode(mapper.readTree(input));
		if (sourcesNode == null || !sourcesNode.isArray()) {
			throw new IllegalArgumentException("document must be an array or an object with a 'sources' array");
		}
		List<Source> sources = new ArrayList<>();
		for (JsonNode sourceNode : sourcesNode) {
			sources.add(mapper.treeToValue(sourceNode, Source.class));
		}
		return sources;
	}

	private JsonNode resolveSourcesNode(JsonNode root) {
		if (root == null) {
			return null;
		}
		return root.isArray() ? root : root.get("sources");
	}

	private boolean isYaml(String filename) {
		if (filename == null) {
			return true;
		}
		String lowercase = filename.toLowerCase();
		return lowercase.endsWith(".yaml") || lowercase.endsWith(".yml");
	}

}
