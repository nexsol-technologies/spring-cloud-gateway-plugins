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

package ch.nexsol.gateway.routes.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Resolves the configured resource locations and aggregates the route definitions parsed
 * from every matching JSON/YAML file.
 */
public class FileRouteDefinitionLoader {

	private static final Logger LOG = LoggerFactory.getLogger(FileRouteDefinitionLoader.class);

	private final RouteDefinitionFileParser parser;

	private final List<String> locations;

	private final ResourcePatternResolver resolver;

	/**
	 * Creates the loader.
	 * @param parser the single-file parser
	 * @param locations the configured resource patterns
	 * @param resolver the resolver used to expand the resource patterns
	 */
	public FileRouteDefinitionLoader(RouteDefinitionFileParser parser, List<String> locations,
			ResourcePatternResolver resolver) {
		this.parser = parser;
		this.locations = locations;
		this.resolver = resolver;
	}

	/**
	 * Reads and parses every readable file matched by the configured locations.
	 * @return the aggregated route definitions
	 */
	public List<RouteDefinition> load() {
		List<RouteDefinition> definitions = new ArrayList<>();
		for (Resource resource : resolveResources()) {
			if (!resource.isReadable()) {
				continue;
			}
			String filename = resource.getFilename();
			try (InputStream input = resource.getInputStream()) {
				definitions.addAll(this.parser.parse(filename, input));
			}
			catch (IOException ex) {
				throw new RouteDefinitionFileException("Unable to read routes file '" + filename + "'", ex);
			}
		}
		LOG.info("Loaded {} route definition(s) from {} location(s)", definitions.size(), this.locations.size());
		return definitions;
	}

	/**
	 * Returns the filesystem directories containing the resolved {@code file:} resources,
	 * so that they can be watched for changes. Non-filesystem resources are ignored.
	 * @return the directories to watch
	 */
	public Set<Path> watchableDirectories() {
		Set<Path> directories = new LinkedHashSet<>();
		for (Resource resource : resolveResources()) {
			try {
				if (resource.isFile()) {
					Path parent = resource.getFile().toPath().toAbsolutePath().getParent();
					if (parent != null) {
						directories.add(parent);
					}
				}
			}
			catch (IOException ex) {
				LOG.debug("Skipping non-watchable resource {}", resource, ex);
			}
		}
		return directories;
	}

	private List<Resource> resolveResources() {
		List<Resource> resources = new ArrayList<>();
		for (String location : this.locations) {
			try {
				resources.addAll(List.of(this.resolver.getResources(location)));
			}
			catch (IOException ex) {
				throw new RouteDefinitionFileException("Unable to resolve routes location '" + location + "'", ex);
			}
		}
		return resources;
	}

}
