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

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the authorities the converter reads out of a JWT for the configured JSON paths.
 */
class ConfigurableJwtGrantedAuthoritiesConverterTests {

	private static Jwt.Builder jwt() {
		return Jwt.withTokenValue("token").header("alg", "none").subject("1234");
	}

	private static List<String> authorities(AbstractAuthenticationToken token) {
		return token.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
	}

	@Test
	void readsAListOfRolesUnderTheConfiguredPath() {
		Jwt token = jwt().claim("realm_access", Map.of("roles", List.of("admin", "user"))).build();

		AbstractAuthenticationToken authentication = new ConfigurableJwtGrantedAuthoritiesConverter(
				List.of("$.realm_access.roles"))
			.convert(token);

		assertThat(authorities(authentication)).containsExactly("admin", "user");
	}

	@Test
	void splitsACommaSeparatedClaim() {
		Jwt token = jwt().claim("permissions", "admin,user").build();

		AbstractAuthenticationToken authentication = new ConfigurableJwtGrantedAuthoritiesConverter(
				List.of("$.permissions"))
			.convert(token);

		assertThat(authorities(authentication)).containsExactly("admin", "user");
	}

	@Test
	void flattensACollectionOfCollections() {
		Jwt token = jwt().claim("groups", List.of(List.of("admin"), List.of("user"))).build();

		AbstractAuthenticationToken authentication = new ConfigurableJwtGrantedAuthoritiesConverter(List.of("$.groups"))
			.convert(token);

		assertThat(authorities(authentication)).containsExactly("admin", "user");
	}

	@Test
	void mergesTheRolesFoundUnderEveryConfiguredPath() {
		Jwt token = jwt().claim("realm_access", Map.of("roles", List.of("admin")))
			.claim("roles", List.of("user"))
			.build();

		AbstractAuthenticationToken authentication = new ConfigurableJwtGrantedAuthoritiesConverter(
				List.of("$.realm_access.roles", "$.roles"))
			.convert(token);

		assertThat(authorities(authentication)).containsExactly("admin", "user");
	}

	@Test
	void keepsARoleOnceWhenSeveralPathsCarryIt() {
		Jwt token = jwt().claim("realm_access", Map.of("roles", List.of("admin")))
			.claim("roles", List.of("admin"))
			.build();

		AbstractAuthenticationToken authentication = new ConfigurableJwtGrantedAuthoritiesConverter(
				List.of("$.realm_access.roles", "$.roles"))
			.convert(token);

		assertThat(authorities(authentication)).containsExactly("admin");
	}

	@Test
	void keepsTheScopesTheStandardConverterReads() {
		Jwt token = jwt().claim("scope", "read write").claim("roles", List.of("admin")).build();

		AbstractAuthenticationToken authentication = new ConfigurableJwtGrantedAuthoritiesConverter(List.of("$.roles"))
			.convert(token);

		assertThat(authorities(authentication)).containsExactly("SCOPE_read", "SCOPE_write", "admin");
	}

	@Test
	void ignoresAPathTheTokenDoesNotCarry() {
		Jwt token = jwt().claim("roles", List.of("admin")).build();

		AbstractAuthenticationToken authentication = new ConfigurableJwtGrantedAuthoritiesConverter(
				List.of("$.resource_access.gateway.roles", "$.roles"))
			.convert(token);

		assertThat(authorities(authentication)).containsExactly("admin");
	}

	@Test
	void ignoresAnEmptyRoleCollection() {
		Jwt token = jwt().claim("roles", List.of()).build();

		AbstractAuthenticationToken authentication = new ConfigurableJwtGrantedAuthoritiesConverter(List.of("$.roles"))
			.convert(token);

		assertThat(authorities(authentication)).isEmpty();
	}

	@Test
	void ignoresAClaimThatIsNeitherAStringNorACollection() {
		Jwt token = jwt().claim("roles", 42).build();

		AbstractAuthenticationToken authentication = new ConfigurableJwtGrantedAuthoritiesConverter(List.of("$.roles"))
			.convert(token);

		assertThat(authorities(authentication)).isEmpty();
	}

	@Test
	void readsNoRoleWhenNoPathIsConfigured() {
		Jwt token = jwt().claim("roles", List.of("admin")).build();

		AbstractAuthenticationToken authentication = new ConfigurableJwtGrantedAuthoritiesConverter(null)
			.convert(token);

		assertThat(authorities(authentication)).isEmpty();
	}

	@Test
	void prefersThePreferredUsernameAsPrincipalName() {
		Jwt token = jwt().claim("preferred_username", "alice").claim("name", "Alice A.").build();

		assertThat(new ConfigurableJwtGrantedAuthoritiesConverter(List.of("$.roles")).convert(token).getName())
			.isEqualTo("alice");
	}

	@Test
	void fallsBackToTheNameThenToTheSubject() {
		Jwt withName = jwt().claim("name", "Alice A.").build();
		Jwt withSubjectOnly = jwt().build();

		ConfigurableJwtGrantedAuthoritiesConverter converter = new ConfigurableJwtGrantedAuthoritiesConverter(
				List.of("$.roles"));

		assertThat(converter.convert(withName).getName()).isEqualTo("Alice A.");
		assertThat(converter.convert(withSubjectOnly).getName()).isEqualTo("1234");
	}

	@Test
	void fallsBackToUnknownWhenTheTokenNamesNobody() {
		Jwt anonymous = Jwt.withTokenValue("token").header("alg", "none").claim("roles", List.of("admin")).build();

		assertThat(new ConfigurableJwtGrantedAuthoritiesConverter(List.of("$.roles")).convert(anonymous).getName())
			.isEqualTo("unknown");
	}

}
