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

package ch.nexsol.gateway.openapi.hub;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.utils.Constants;

import org.springframework.beans.factory.ObjectProvider;

/**
 * The paths the OpenAPI hub serves, resolved from the SpringDoc configuration as it is
 * actually set.
 * <p>
 * This is the authoritative list of what the hub answers, and the single source both the
 * security chain and the audit exclusion read: a path opened by one and audited by the
 * other would be a discrepancy nobody notices until it matters.
 */
public final class HubOpenapiPaths {

	private HubOpenapiPaths() {
	}

	/**
	 * Resolves the documentation paths of the hub.
	 * <p>
	 * A custom {@code springdoc.api-docs.path} or {@code springdoc.swagger-ui.path} is
	 * honoured, falling back to the SpringDoc defaults when the configuration is absent.
	 * The aggregated contracts carry a service name only known at runtime, so they are
	 * matched one segment deep ({@code /v3/api-docs/*}) rather than with a {@code /**}
	 * pattern, which would also swallow paths the hub does not serve.
	 * @param springDocProperties the SpringDoc configuration, when it is on
	 * @param swaggerUiProperties the Swagger UI configuration, when it is on
	 * @return the paths served by the hub, in a stable order
	 */
	public static List<String> documentationPaths(ObjectProvider<SpringDocConfigProperties> springDocProperties,
			ObjectProvider<SwaggerUiConfigProperties> swaggerUiProperties) {
		String apiDocs = springDocProperties.stream()
			.map((properties) -> properties.getApiDocs().getPath())
			.findFirst()
			.orElse(Constants.DEFAULT_API_DOCS_URL);
		String swaggerUi = swaggerUiProperties.stream()
			.map(SwaggerUiConfigProperties::getPath)
			.filter((path) -> path != null)
			.findFirst()
			.orElse(Constants.DEFAULT_SWAGGER_UI_PATH);
		Set<String> paths = new LinkedHashSet<>();
		// The contract of the gateway itself, and the SpringDoc endpoints driving the UI.
		paths.add(apiDocs);
		paths.add(apiDocs + ".yaml");
		paths.add(apiDocs + "/swagger-config");
		paths.add(swaggerUi);
		// The aggregated contracts, published as gateway routes named after each service.
		paths.add(HubDiscoveryRouteLocator.API_DOCS_URL + "/*");
		// The Swagger UI assets, whose file names belong to the shipped webjar.
		paths.add(Constants.SWAGGER_UI_PREFIX + "/**");
		paths.add(Constants.DEFAULT_WEB_JARS_PREFIX_URL + Constants.SWAGGER_UI_PREFIX + "/**");
		return List.copyOf(paths);
	}

}
