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

import java.net.URI;
import java.time.Duration;
import java.util.List;

import ch.nexsol.gateway.commons.routes.RouteDefinitionSources;
import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.support.NameUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The contracts the OpenAPI hub aggregates, read as tools. The hub publishes one
 * documentation route per service, which proxies the contract and rewrites its
 * {@code servers} to the gateway; that route is the catalog here: it names the service,
 * where its contract is and under which prefix the gateway serves it. The contract is
 * read from the service, as the hub reads it, and its operations become the tools of the
 * service.
 */
public class ContractCatalog implements ApiToolSource {

	private static final Logger LOG = LoggerFactory.getLogger(ContractCatalog.class);

	private static final Duration SOURCE_TIMEOUT = Duration.ofSeconds(5);

	private static final String REWRITE_PATH = "RewritePath";

	private static final String ADVERTISED_PATH = "OpenapiModifyResponseBody";

	private final ObjectProvider<RouteDefinitionLocator> locators;

	private final WebClient webClient;

	private final URI gatewayUri;

	private final McpHubProperties properties;

	public ContractCatalog(ObjectProvider<RouteDefinitionLocator> locators, WebClient webClient, URI gatewayUri,
			McpHubProperties properties) {
		this.locators = locators;
		this.webClient = webClient;
		this.gatewayUri = gatewayUri;
		this.properties = properties;
	}

	@Override
	public Mono<List<ApiTool>> load() {
		return Flux.fromIterable(RouteDefinitionSources.sources(this.locators))
			.flatMap((locator) -> RouteDefinitionSources.read(locator, SOURCE_TIMEOUT).onErrorResume((ex) -> {
				LOG.warn("Route definition source {} could not be read", RouteDefinitionSources.name(locator), ex);
				return Mono.just(List.of());
			}))
			.flatMapIterable((definitions) -> definitions)
			.mapNotNull((definition) -> service(definition, this.gatewayUri))
			.flatMap((service) -> tools(service).onErrorResume((ex) -> {
				LOG.warn("The contract of {} could not be read from {}: {}", service.name(), service.contractUrl(),
						ex.getMessage());
				return Mono.just(List.<ApiTool>of());
			}), this.properties.getConcurrency())
			.flatMapIterable((tools) -> tools)
			.collectList();
	}

	private Mono<List<ApiTool>> tools(DocumentedService service) {
		return this.webClient.get()
			.uri(service.contractUrl())
			.retrieve()
			.bodyToMono(String.class)
			.publishOn(Schedulers.boundedElastic())
			.map((contents) -> OperationTools.of(service.name(), parse(service, contents), service.base(),
					this.properties.isWriteEnabled(), this.properties.isConfirmWrites()));
	}

	private static OpenAPI parse(DocumentedService service, String contents) {
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		SwaggerParseResult result = new OpenAPIV3Parser().readContents(contents, null, options);
		if (result == null || result.getOpenAPI() == null) {
			throw new IllegalStateException("not an OpenAPI document: "
					+ ((result != null) ? String.valueOf(result.getMessages()) : "no result"));
		}
		LOG.debug("Read the contract of {} from {}", service.name(), service.contractUrl());
		return result.getOpenAPI();
	}

	/**
	 * The service a documentation route of the hub documents.
	 * @param definition a route definition
	 * @param gatewayUri the address the gateway serves the services under
	 * @return the service, or {@code null} when the route is not one of the hub
	 */
	static DocumentedService service(RouteDefinition definition, URI gatewayUri) {
		String id = definition.getId();
		if (id == null || !id.startsWith(HubDiscoveryRouteLocator.ROUTE_ID_PREFIX) || definition.getUri() == null) {
			return null;
		}
		String contractPath = null;
		String prefix = "/";
		for (FilterDefinition filter : definition.getFilters()) {
			if (REWRITE_PATH.equals(filter.getName())) {
				contractPath = filter.getArgs().get(NameUtils.GENERATED_NAME_PREFIX + 1);
			}
			else if (ADVERTISED_PATH.equals(filter.getName())) {
				prefix = filter.getArgs().getOrDefault(NameUtils.GENERATED_NAME_PREFIX + 0, "/");
			}
		}
		if (!StringUtils.hasText(contractPath)) {
			return null;
		}
		Object name = definition.getMetadata().get("name");
		String service = (name != null) ? name.toString()
				: id.substring(HubDiscoveryRouteLocator.ROUTE_ID_PREFIX.length());
		String base = gatewayUri.toString();
		if (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		String advertised = "/".equals(prefix) ? "" : prefix;
		return new DocumentedService(service, URI.create(definition.getUri() + contractPath),
				URI.create(base + advertised));
	}

	/**
	 * A service the hub documents.
	 *
	 * @param name the service name, which prefixes its tools
	 * @param contractUrl where its contract is read from
	 * @param base the address the gateway serves its operations under
	 */
	record DocumentedService(String name, URI contractUrl, URI base) {
	}

}
