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

package ch.nexsol.gateway.database.autoconfigure;

import ch.nexsol.gateway.commons.security.SecuredPaths;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests what this module declares about the paths it serves: reaching its API creates,
 * changes and deletes routes, so it asks for a principal whatever the console in front of
 * it does.
 */
class GatewayDatabaseSecuredPathsTests {

	@Test
	void declaresTheRouteApiAsChangingTheGateway() {
		SecuredPaths declared = new GatewayDatabaseAutoConfiguration.RouteApiConfiguration().routeApiSecuredPaths();
		assertThat(declared.writePaths()).contains("/api/gateway/routes", "/api/gateway/routes/{id}",
				"/api/gateway/routes/available-predicates", "/api/gateway/routes/available-filters");
		// A client calls them with a token and a JSON body, holding no session and
		// therefore no CSRF token to send back.
		assertThat(declared.csrfExemptPaths()).isEqualTo(declared.writePaths());
		assertThat(declared.paths()).isEmpty();
		assertThat(declared.openPaths()).isEmpty();
	}

	@Test
	void declaresNoPathAsBrowsableWithoutAPrincipal() {
		// The API answers on the same prefix it creates routes under, so none of it is a
		// read-only surface somebody could be let into without a principal.
		assertThat(new GatewayDatabaseAutoConfiguration.RouteApiConfiguration().routeApiSecuredPaths().openPaths())
			.isEmpty();
	}

}
