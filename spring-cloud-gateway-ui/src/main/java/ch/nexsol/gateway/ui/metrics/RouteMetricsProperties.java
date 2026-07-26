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

package ch.nexsol.gateway.ui.metrics;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the traffic view.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.ui.traffic")
public class RouteMetricsProperties {

	/**
	 * Default exclusion: the documentation routes the OpenAPI hub publishes. They carry
	 * contracts, not traffic, and their volume says nothing about how the gateway is
	 * used.
	 */
	public static final String OPENAPI_DOCS_PATTERN = "openapi-docs-.*";

	/**
	 * Regular expressions matched against the route id: a route matching any of them is
	 * left out of the traffic view and of the traffic figures on the home page. The whole
	 * id must match. Set to an empty list to show every route.
	 */
	private List<String> excludedRoutes = new ArrayList<>(List.of(OPENAPI_DOCS_PATTERN));

	public List<String> getExcludedRoutes() {
		return this.excludedRoutes;
	}

	public void setExcludedRoutes(List<String> excludedRoutes) {
		this.excludedRoutes = excludedRoutes;
	}

}
