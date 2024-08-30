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

package ch.nexsol.gateway.filter.factory;

import java.text.ParseException;
import java.util.List;

import ch.nexsol.gateway.filter.factory.AuthorizationTokenGatewayFilterFactory.GrantAccess;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationTokenGatewayFilterFactoryTests {

	private static final String token = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJEaW5vQ2hpZXNhLmdpdGh1Yi5pbyIsInN1YiI6InRhbWFyYSIsImF1ZCI6Im9sYWYiLCJpYXQiOjE3MDcwNDI5MTcsImV4cCI6MTcwNzA0MzUxNywiYWFhIjp0cnVlLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsib2ZmbGluZV9hY2Nlc3MiLCJhZG1pbiIsImRlZmF1bHQtcm9sZXMtZXhhbXBsZSIsInVtYV9hdXRob3JpemF0aW9uIiwidXNlciJdfSwicmVzb3VyY2VfYWNjZXNzIjp7InNwcmluZy1jbGllbnQtMSI6eyJyb2xlcyI6WyJyb2xlMSJdfSwic3ByaW5nLWNsaWVudC0yIjp7InJvbGVzIjpbInJvbGUyIl19fX0.d_0IPTyC4aFoKtm3artyDWq8oQgl1Pi7S7m4xV-tUYJVHDtdPHWg8IFDdrBuC86sZVAmKNwTa8H9OYMEJvxFIDFUnOc3mFQrLYWAMurYE9jYV5AkbatVf1-o7zUuQ4y4qnmMLlqK-AQsiqRo7lvyWIygqJFfTnkmekUcQTOFq-uxXL1mAlvh7eC42y9JCBGqJc9FNc-0ZtoId022BG1Mu8_RyaYrtF5T_ZgAwoUcfXQSq8q_p6ejMofjk8bVJ8D_9CbuvexzUZnmeP3fv8oTG_s2VVrp9sJkgue2FmhLTh4g7lga00r9vH6xpQr59_Lu5O5rcyAS2Aa6VaJpljWKJQ";

	@Test
	void shouldNotHaveNoRole() throws ParseException {
		AuthorizationTokenGatewayFilterFactory factory = new AuthorizationTokenGatewayFilterFactory();
		JWT jwt = JWTParser.parse(token);
		GrantAccess grantAccess = new GrantAccess();
		grantAccess.setJsonPath("$.resource_access.*.roles");
		grantAccess.setRoles(List.of("roleXXX"));

		boolean hasAuthority = factory.hasAuthority(jwt.getJWTClaimsSet().getClaims(), grantAccess);
		assertThat(hasAuthority).isFalse();
	}

	@Test
	void shouldHaveOneRole() throws ParseException {
		AuthorizationTokenGatewayFilterFactory factory = new AuthorizationTokenGatewayFilterFactory();
		JWT jwt = JWTParser.parse(token);
		GrantAccess grantAccess = new GrantAccess();
		grantAccess.setJsonPath("$.resource_access.*.roles");
		grantAccess.setRoles(List.of("role1"));

		boolean hasAuthority = factory.hasAuthority(jwt.getJWTClaimsSet().getClaims(), grantAccess);
		assertThat(hasAuthority).isTrue();
	}

	@Test
	void shouldHaveAllRole() throws ParseException {
		AuthorizationTokenGatewayFilterFactory factory = new AuthorizationTokenGatewayFilterFactory();
		JWT jwt = JWTParser.parse(token);
		GrantAccess grantAccess = new GrantAccess();
		grantAccess.setJsonPath("$.resource_access.*.roles");
		grantAccess.setRoles(List.of("role1", "role2"));

		boolean hasAuthority = factory.hasAuthority(jwt.getJWTClaimsSet().getClaims(), grantAccess);
		assertThat(hasAuthority).isTrue();
	}

	@Test
	void shouldNotHaveOneOfAllRole() throws ParseException {
		AuthorizationTokenGatewayFilterFactory factory = new AuthorizationTokenGatewayFilterFactory();
		JWT jwt = JWTParser.parse(token);
		GrantAccess grantAccess = new GrantAccess();
		grantAccess.setJsonPath("$.resource_access.*.roles");
		grantAccess.setRoles(List.of("role1", "roleXXX"));

		boolean hasAuthority = factory.hasAuthority(jwt.getJWTClaimsSet().getClaims(), grantAccess);
		assertThat(hasAuthority).isFalse();
	}

}
