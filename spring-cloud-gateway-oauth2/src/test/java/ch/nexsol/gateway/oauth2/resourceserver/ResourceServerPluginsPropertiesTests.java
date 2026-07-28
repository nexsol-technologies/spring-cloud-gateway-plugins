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

package ch.nexsol.gateway.oauth2.resourceserver;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ch.nexsol.gateway.oauth2.resourceserver.ResourceServerPluginsProperties.OAuth2ResourceServerProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the constraints on the multi-tenant list still reach each tenant, now that
 * the cascade is declared on the type argument rather than on the container itself.
 */
class ResourceServerPluginsPropertiesTests {

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
	void acceptsAFullyConfiguredTenant() {
		ResourceServerPluginsProperties properties = properties(tenant("acme", "https://issuer/acme"));

		assertThat(validator.validate(properties)).isEmpty();
	}

	@Test
	void reportsATenantWithoutId() {
		ResourceServerPluginsProperties properties = properties(tenant("", "https://issuer/acme"));

		Set<ConstraintViolation<ResourceServerPluginsProperties>> violations = validator.validate(properties);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath()).hasToString("multitenant[0].id"));
	}

	@Test
	void reportsATenantWithoutIssuerUri() {
		OAuth2ResourceServerProperties tenant = new OAuth2ResourceServerProperties();
		tenant.setId("acme");
		ResourceServerPluginsProperties properties = properties(tenant);

		Set<ConstraintViolation<ResourceServerPluginsProperties>> violations = validator.validate(properties);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath()).hasToString("multitenant[0].issuerUri"));
	}

	@Test
	void reportsANullTenant() {
		ResourceServerPluginsProperties properties = properties((OAuth2ResourceServerProperties) null);

		Set<ConstraintViolation<ResourceServerPluginsProperties>> violations = validator.validate(properties);

		assertThat(violations).singleElement()
			.satisfies((violation) -> assertThat(violation.getPropertyPath())
				.hasToString("multitenant[0].<list element>"));
	}

	@Test
	void acceptsAnEmptyMultitenantList() {
		assertThat(validator.validate(new ResourceServerPluginsProperties())).isEmpty();
	}

	private static ResourceServerPluginsProperties properties(OAuth2ResourceServerProperties... tenants) {
		ResourceServerPluginsProperties properties = new ResourceServerPluginsProperties();
		List<OAuth2ResourceServerProperties> multitenant = new ArrayList<>();
		for (OAuth2ResourceServerProperties tenant : tenants) {
			multitenant.add(tenant);
		}
		properties.setMultitenant(multitenant);
		return properties;
	}

	private static OAuth2ResourceServerProperties tenant(String id, String issuerUri) {
		OAuth2ResourceServerProperties tenant = new OAuth2ResourceServerProperties();
		tenant.setId(id);
		tenant.setIssuerUri(URI.create(issuerUri));
		return tenant;
	}

}
