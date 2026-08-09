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

package ch.nexsol.gateway.oauth2.filter.factory;

import java.util.List;
import java.util.Set;

import ch.nexsol.gateway.oauth2.filter.factory.AuthorizationTokenGatewayFilterFactory.Config;
import ch.nexsol.gateway.oauth2.filter.factory.AuthorizationTokenGatewayFilterFactory.GrantAccess;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the constraints of the filter configuration: the element constraints on the
 * string lists and the cascade into each granted access.
 */
class AuthorizationTokenConfigValidationTests {

	private static ValidatorFactory validatorFactory;

	private static Validator validator;

	@BeforeAll
	static void setUp() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void tearDown() {
		validatorFactory.close();
	}

	@Test
	void acceptsAFullyConfiguredFilter() {
		Config config = new Config();
		config.setIssuers(List.of("https://issuer"));
		config.setClientIds(List.of("cid"));
		config.setGrantAccesses(List.of(grantAccess("$.realm_access.roles", List.of("admin"))));

		assertThat(validator.validate(config)).isEmpty();
	}

	@Test
	void reportsABlankIssuer() {
		Config config = new Config();
		config.setIssuers(List.of(""));

		Set<ConstraintViolation<Config>> violations = validator.validate(config);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath()).hasToString("issuers[0].<list element>"));
	}

	@Test
	void reportsABlankClientId() {
		Config config = new Config();
		config.setClientIds(List.of(""));

		Set<ConstraintViolation<Config>> violations = validator.validate(config);

		assertThat(violations).singleElement()
			.satisfies(
					(violation) -> assertThat(violation.getPropertyPath()).hasToString("clientIds[0].<list element>"));
	}

	@Test
	void cascadesIntoEachGrantedAccess() {
		Config config = new Config();
		config.setGrantAccesses(List.of(grantAccess("", List.of("admin"))));

		Set<ConstraintViolation<Config>> violations = validator.validate(config);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath()).hasToString("grantAccesses[0].jsonPath"));
	}

	@Test
	void reportsABlankRoleInsideAGrantedAccess() {
		Config config = new Config();
		config.setGrantAccesses(List.of(grantAccess("$.realm_access.roles", List.of(""))));

		Set<ConstraintViolation<Config>> violations = validator.validate(config);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath())
				.hasToString("grantAccesses[0].roles[0].<list element>"));
	}

	@Test
	void reportsGrantedAccessWithoutRoles() {
		Config config = new Config();
		config.setGrantAccesses(List.of(grantAccess("$.realm_access.roles", List.of())));

		Set<ConstraintViolation<Config>> violations = validator.validate(config);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath()).hasToString("grantAccesses[0].roles"));
	}

	@Test
	void reportsAMissingMatchMode() {
		Config config = new Config();
		config.setGrantAccessesMatch(null);

		Set<ConstraintViolation<Config>> violations = validator.validate(config);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath()).hasToString("grantAccessesMatch"));
	}

	@Test
	void reportsAMissingMatchModeInsideAGrantedAccess() {
		GrantAccess grantAccess = grantAccess("$.realm_access.roles", List.of("admin"));
		grantAccess.setMatch(null);
		Config config = new Config();
		config.setGrantAccesses(List.of(grantAccess));

		Set<ConstraintViolation<Config>> violations = validator.validate(config);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath()).hasToString("grantAccesses[0].match"));
	}

	@Test
	void acceptsAnEmptyConfiguration() {
		assertThat(validator.validate(new Config())).isEmpty();
	}

	private static GrantAccess grantAccess(String jsonPath, List<String> roles) {
		GrantAccess grantAccess = new GrantAccess();
		grantAccess.setJsonPath(jsonPath);
		grantAccess.setRoles(roles);
		return grantAccess;
	}

}
