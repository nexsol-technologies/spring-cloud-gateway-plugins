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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;

/**
 * Reads the OpenAPI contracts the filter validates against, and keeps each one for as
 * long as the gateway runs.
 * <p>
 * Reading and parsing a document blocks, so it happens on the bounded elastic scheduler
 * and only once per location: the first request through a route pays for it, every later
 * one gets the parsed contract straight away. A load that failed is not remembered, so a
 * contract that became reachable again is picked up by the next request rather than
 * needing a restart.
 */
public class OpenapiContractRegistry {

	private final Map<String, Mono<OpenapiContract>> contracts = new ConcurrentHashMap<>();

	private final ResourceLoader resourceLoader;

	/**
	 * Creates the registry.
	 * @param resourceLoader the loader used to reach a contract that is not addressed by
	 * an {@code http(s)} URL
	 */
	public OpenapiContractRegistry(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	/**
	 * Returns the contract at the given location, reading it on first use.
	 * @param location the contract location: an {@code http(s)} URL, or anything the
	 * resource loader understands such as {@code classpath:} or {@code file:}
	 * @return a mono emitting the parsed contract, failing with
	 * {@link OpenapiContractException} when it cannot be read
	 */
	public Mono<OpenapiContract> contract(String location) {
		return this.contracts.computeIfAbsent(location,
				(key) -> Mono.fromCallable(() -> new OpenapiContract(read(key)))
					.subscribeOn(Schedulers.boundedElastic())
					// Keep the parsed contract for every later request, but forget a
					// failure so a transient one does not disable validation for good.
					.cache()
					.doOnError((ex) -> this.contracts.remove(key)));
	}

	private OpenAPI read(String location) {
		if (!StringUtils.hasText(location)) {
			throw new OpenapiContractException("The OpenAPI validation filter is missing its contract location");
		}
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		SwaggerParseResult result = isUrl(location) ? new OpenAPIV3Parser().readLocation(location, null, options)
				: new OpenAPIV3Parser().readContents(contents(location), null, options);
		if (result == null || result.getOpenAPI() == null) {
			String messages = (result != null) ? String.valueOf(result.getMessages()) : "no result";
			throw new OpenapiContractException(
					"Unable to parse the OpenAPI contract at '" + location + "': " + messages);
		}
		return result.getOpenAPI();
	}

	/**
	 * Reads a contract through the resource loader. A document reached this way should be
	 * self-contained or use absolute references: only a contract given as an
	 * {@code http(s)} URL keeps a base its relative {@code $ref} can resolve against.
	 */
	private String contents(String location) {
		Resource resource = this.resourceLoader.getResource(location);
		if (!resource.exists()) {
			throw new OpenapiContractException("The OpenAPI contract '" + location + "' does not exist");
		}
		try (InputStream input = resource.getInputStream()) {
			return new String(FileCopyUtils.copyToByteArray(input), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new OpenapiContractException("Unable to read the OpenAPI contract '" + location + "'", ex);
		}
	}

	private static boolean isUrl(String location) {
		return location.startsWith("http://") || location.startsWith("https://");
	}

}
