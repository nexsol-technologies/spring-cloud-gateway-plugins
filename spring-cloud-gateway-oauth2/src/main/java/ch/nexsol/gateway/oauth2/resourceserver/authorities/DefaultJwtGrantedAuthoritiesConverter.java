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

package ch.nexsol.gateway.oauth2.resourceserver.authorities;

import java.util.List;

/**
 * Default JWT granted authorities converter.
 */
public class DefaultJwtGrantedAuthoritiesConverter extends ConfigurableJwtGrantedAuthoritiesConverter {

	private static final String JSONPATH_KEYCLOAK_REALM_ACCESS = "$.realm_access.roles";

	private static final String JSONPATH_KEYCLOAK_RESOURCE_ACCESS_START = "$.resource_access.";

	private static final String JSONPATH_KEYCLOAK_RESOURCE_ACCESS_END = ".roles";

	private static final String JSONPATH_PERMISSIONS_ROLES = "$.permissions";

	private static final String JSONPATH_DEFAULT_ROLES = "$.roles";

	/**
	 * Create a converter with the default Keycloak-oriented JSON path expressions,
	 * including the resource-specific roles for the given resource name.
	 * @param resourceName the resource (client) name whose roles are resolved
	 */
	public DefaultJwtGrantedAuthoritiesConverter(String resourceName) {
		super(List.of(JSONPATH_KEYCLOAK_REALM_ACCESS,
				JSONPATH_KEYCLOAK_RESOURCE_ACCESS_START + resourceName + JSONPATH_KEYCLOAK_RESOURCE_ACCESS_END,
				JSONPATH_PERMISSIONS_ROLES, JSONPATH_DEFAULT_ROLES));
	}

}
