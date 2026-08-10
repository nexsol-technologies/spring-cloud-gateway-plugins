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

package ch.nexsol.gateway.ui.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ClaimRoles}, over the shapes the providers actually put roles in.
 */
class ClaimRolesTests {

	@Test
	void shouldReadAFlatClaim() {
		Map<String, Object> claims = Map.of("roles", List.of("admin", "reader"));
		assertThat(names(ClaimRoles.from(claims, "roles"))).containsExactly("ROLE_ADMIN", "ROLE_READER");
	}

	@Test
	void shouldWalkANestedClaim() {
		Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("operator")));
		assertThat(names(ClaimRoles.from(claims, "realm_access.roles"))).containsExactly("ROLE_OPERATOR");
	}

	@Test
	void shouldNotPrefixARoleThatAlreadyCarriesThePrefix() {
		Map<String, Object> claims = Map.of("roles", List.of("ROLE_ADMIN"));
		assertThat(names(ClaimRoles.from(claims, "roles"))).containsExactly("ROLE_ADMIN");
	}

	@Test
	void shouldYieldNothingWhenThePathLeadsNowhere() {
		Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("operator")));
		assertThat(ClaimRoles.from(claims, "resource_access.console.roles")).isEmpty();
		assertThat(ClaimRoles.from(Map.of(), "roles")).isEmpty();
	}

	@Test
	void shouldYieldNothingWhenTheClaimIsNotAListOfRoles() {
		assertThat(ClaimRoles.from(Map.of("roles", "admin"), "roles")).isEmpty();
		assertThat(ClaimRoles.from(Map.of("roles", List.of(1, 2)), "roles")).isEmpty();
	}

	private static List<String> names(Collection<GrantedAuthority> authorities) {
		return authorities.stream().map(GrantedAuthority::getAuthority).toList();
	}

}
