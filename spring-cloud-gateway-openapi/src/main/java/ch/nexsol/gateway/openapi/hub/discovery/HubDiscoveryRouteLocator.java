/*
 * Copyright 2024 the original author or authors.
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

package ch.nexsol.gateway.openapi.hub.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.openapi.hub.OpenapiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.discovery.DiscoveryClientRouteDefinitionLocator;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.util.StringUtils;

public class HubDiscoveryRouteLocator extends DiscoveryClientRouteDefinitionLocator {

	public static final String ROUTE_ID_PREFIX = "openapi-docs-discovery-";

	private static final Logger LOG = LoggerFactory.getLogger(HubDiscoveryRouteLocator.class);

	private static final String API_DOCS_URL = "/v3/api-docs";

	private final OpenapiService openapiService;

	// because it's private in DiscoveryClientRouteDefinitionLocator
	private final String routeIdPrefix;

	public HubDiscoveryRouteLocator(ReactiveDiscoveryClient discoveryClient, DiscoveryLocatorProperties properties,
			OpenapiService openapiService) {
		super(discoveryClient, properties);
		this.openapiService = openapiService;

		if (StringUtils.hasText(properties.getRouteIdPrefix())) {
			this.routeIdPrefix = properties.getRouteIdPrefix();
		}
		else {
			this.routeIdPrefix = discoveryClient.getClass().getSimpleName() + "_";
		}
	}

	@Override
	public Flux<RouteDefinition> getRouteDefinitions() {
		return super.getRouteDefinitions()
			// do not process existing api doc routes
			.filter((route) -> !route.getId().startsWith(ROUTE_ID_PREFIX))
			.flatMap((routeDefinition) -> {
				String routeId = routeDefinition.getId().replace(this.routeIdPrefix, "");
				return this.openapiService.discoverOpenapiUrl(routeId, routeDefinition);

			})
			.map((openapiDiscover) -> {
				RouteDefinition routeDefinition = openapiDiscover.routeDefinition();

				// the path to the route to the microservice (ex. lb://MICROSERVICE-A)
				String path = "/" + routeDefinition.getUri().getHost();

				String name = routeDefinition.getId();
				name = name.replace(this.routeIdPrefix, "");

				// create OPENAPI route
				RouteDefinition r = new RouteDefinition();
				r.setId(ROUTE_ID_PREFIX + name);
				r.setUri(routeDefinition.getUri());
				r.setMetadata(Map.of("name", name));

				// filter phase
				List<FilterDefinition> filters = new ArrayList<>();
				FilterDefinition f = new FilterDefinition("RewritePath=" + API_DOCS_URL + path + ", " + API_DOCS_URL);
				filters.add(f);
				f = new FilterDefinition("OpenapiModifyResponseBody=" + path);
				filters.add(f);
				r.setFilters(filters);

				// predicate phase
				List<PredicateDefinition> predicates = new ArrayList<>();
				PredicateDefinition p = new PredicateDefinition("Path=" + API_DOCS_URL + path);
				predicates.add(p);
				r.setPredicates(predicates);

				LOG.debug("Create openapi route {} for existing discovery route {}", r.toString(),
						routeDefinition.toString());

				return r;
			});
	}

}
