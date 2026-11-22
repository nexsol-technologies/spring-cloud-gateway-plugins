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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import io.swagger.v3.oas.models.OpenAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.cloud.gateway.route.RouteDefinition;

/**
 * Aggregates the route definitions generated from every configured OpenAPI source.
 */
public class OpenApiRouteDefinitionLoader {

	private static final Logger LOG = LoggerFactory.getLogger(OpenApiRouteDefinitionLoader.class);

	private static final int CONCURRENCY = 4;

	private final OpenApiSpecLoader specLoader;

	private final OpenApiRouteDefinitionMapper mapper;

	private final OpenapiSourcesLoader sourcesLoader;

	private final Duration contractTtl;

	private final Clock clock;

	private final Map<String, KeptContract> contracts = new ConcurrentHashMap<>();

	/**
	 * Creates the loader.
	 * @param specLoader the OpenAPI document reader
	 * @param mapper the document-to-routes mapper
	 * @param sourcesLoader the resolver of the configured sources
	 */
	public OpenApiRouteDefinitionLoader(OpenApiSpecLoader specLoader, OpenApiRouteDefinitionMapper mapper,
			OpenapiSourcesLoader sourcesLoader) {
		this(specLoader, mapper, sourcesLoader, null);
	}

	/**
	 * Creates the loader, keeping each contract it reads for a while.
	 * @param specLoader the loader fetching and parsing OpenAPI documents
	 * @param mapper the mapper turning a document into route definitions
	 * @param sourcesLoader the loader gathering the sources to read
	 * @param contractTtl how long a contract is kept before it is read again,
	 * {@code null} or zero to read it on every load
	 */
	public OpenApiRouteDefinitionLoader(OpenApiSpecLoader specLoader, OpenApiRouteDefinitionMapper mapper,
			OpenapiSourcesLoader sourcesLoader, Duration contractTtl) {
		this(specLoader, mapper, sourcesLoader, contractTtl, Clock.systemUTC());
	}

	OpenApiRouteDefinitionLoader(OpenApiSpecLoader specLoader, OpenApiRouteDefinitionMapper mapper,
			OpenapiSourcesLoader sourcesLoader, Duration contractTtl, Clock clock) {
		this.specLoader = specLoader;
		this.mapper = mapper;
		this.sourcesLoader = sourcesLoader;
		this.contractTtl = (contractTtl != null && contractTtl.isPositive()) ? contractTtl : null;
		this.clock = clock;
	}

	/**
	 * Lets go of every contract kept, so the next load reads them all again.
	 */
	public void forget() {
		this.contracts.clear();
	}

	/**
	 * Reads every source and generates the corresponding route definitions. The sources
	 * are resolved on each call, so a document declaring them is re-read on every reload.
	 * @return the aggregated route definitions
	 */
	public List<RouteDefinition> load() {
		List<Source> sources = new ArrayList<>();
		for (Source source : this.sourcesLoader.load()) {
			// A source may be declared for its contract alone, so it joins the OpenAPI
			// hub while its routes are declared elsewhere. Its document is not even read
			// here: reading it is the hub's business, and a contract that cannot be
			// parsed into routes nobody asked for is not a failure of this loader.
			if (source.getMode() == RouteGenerationMode.NO_ROUTE) {
				LOG.debug("OpenAPI source '{}' is declared for its contract alone", source.getId());
				continue;
			}
			sources.add(source);
		}
		// Read side by side, a few at a time: read one after the other, every contract
		// out of reach adds its whole connection timeout to the refresh. The order of the
		// sources is kept, since it is the order of their routes.
		List<RouteDefinition> definitions = Flux.fromIterable(sources)
			.flatMapSequential(
					(source) -> Mono.fromCallable(() -> routesOf(source)).subscribeOn(Schedulers.boundedElastic()),
					CONCURRENCY)
			.flatMapIterable((routes) -> routes)
			.collectList()
			.block();
		// The contract of a source that is no longer declared is nobody's to keep.
		this.contracts.keySet().retainAll(sources.stream().map(Source::getSpecUrl).filter(Objects::nonNull).toList());
		LOG.info("Generated {} route definition(s) from {} OpenAPI source(s)", definitions.size(), sources.size());
		return definitions;
	}

	private OpenAPI contractOf(String specUrl) {
		if (this.contractTtl == null || specUrl == null) {
			return this.specLoader.load(specUrl);
		}
		Instant now = this.clock.instant();
		KeptContract kept = this.contracts.get(specUrl);
		if (kept != null && now.isBefore(kept.until())) {
			return kept.contract();
		}
		// A read that fails throws before anything is kept: it is tried again on the next
		// load rather than remembered for the whole time a contract is.
		OpenAPI contract = this.specLoader.load(specUrl);
		this.contracts.put(specUrl, new KeptContract(contract, now.plus(this.contractTtl)));
		return contract;
	}

	private List<RouteDefinition> routesOf(Source source) {
		// Isolate failures per source: a broken one must not drop the others' routes.
		try {
			return this.mapper.toRouteDefinitions(source, contractOf(source.getSpecUrl()));
		}
		catch (RuntimeException ex) {
			LOG.warn("Skipping OpenAPI source '{}' ({}): {}", source.getId(), source.getSpecUrl(), ex.getMessage());
			return List.of();
		}
	}

	private record KeptContract(OpenAPI contract, Instant until) {
	}

}
