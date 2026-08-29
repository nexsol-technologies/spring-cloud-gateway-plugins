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
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the paths the default converter reads without any configuration, including the
 * Keycloak resource roles of the resource it is built for.
 */
class DefaultJwtGrantedAuthoritiesConverterTests {

	private static Jwt.Builder jwt() {
		return Jwt.withTokenValue("token").header("alg", "none").subject("1234");
	}

	private static List<String> authorities(Jwt token, String resourceName) {
		return new DefaultJwtGrantedAuthoritiesConverter(resourceName).convert(token)
			.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.sorted()
			.toList();
	}

	@Test
	void readsTheKeycloakRealmRoles() {
		Jwt token = jwt().claim("realm_access", Map.of("roles", List.of("admin"))).build();

		assertThat(authorities(token, "gateway")).containsExactly("admin");
	}

	@Test
	void readsTheKeycloakRolesOfItsOwnResourceOnly() {
		Jwt token = jwt()
			.claim("resource_access", Map.of("gateway", Map.of("roles", List.of("gateway-admin")), "other",
					Map.of("roles", List.of("other-admin"))))
			.build();

		assertThat(authorities(token, "gateway")).containsExactly("gateway-admin");
	}

	@Test
	void readsThePermissionsAndTheRolesClaims() {
		Jwt token = jwt().claim("permissions", List.of("read")).claim("roles", List.of("user")).build();

		assertThat(authorities(token, "gateway")).containsExactly("read", "user");
	}

	@Test
	void readsNothingFromATokenCarryingNoRoleClaim() {
		assertThat(authorities(jwt().build(), "gateway")).isEmpty();
	}

}
