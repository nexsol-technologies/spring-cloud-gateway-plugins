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

import org.junit.jupiter.api.Test;

import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Unit tests for {@link GatewayIssuers}, which reads the issuers the gateway already
 * validates its routed traffic against rather than asking for them a second time.
 */
class GatewayIssuersTests {

	private static final String WELL_KNOWN = "/.well-known/openid-configuration";

	private final MockEnvironment environment = new MockEnvironment();

	@Test
	void readsNothingFromAGatewayThatValidatesNothing() {
		assertThat(GatewayIssuers.from(this.environment)).isEmpty();
	}

	@Test
	void readsTheMultiTenantIssuersUnderTheirTenantId() {
		this.environment.withProperty("spring.security.oauth2.resourceserver.multitenant[0].id", "local")
			.withProperty("spring.security.oauth2.resourceserver.multitenant[0].issuer-uri", "http://localhost:9090")
			.withProperty("spring.security.oauth2.resourceserver.multitenant[1].id", "partner")
			.withProperty("spring.security.oauth2.resourceserver.multitenant[1].issuer-uri",
					"https://partner.example.ch/realms/care");

		assertThat(GatewayIssuers.from(this.environment)).containsExactly(
				entry("local", "http://localhost:9090" + WELL_KNOWN),
				entry("partner", "https://partner.example.ch/realms/care" + WELL_KNOWN));
	}

	@Test
	void readsTheSingleIssuerOfTheSpringProperty() {
		this.environment.withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://localhost:9090");

		assertThat(GatewayIssuers.from(this.environment))
			.containsExactly(entry(GatewayIssuers.JWT_ISSUER_NAME, "http://localhost:9090" + WELL_KNOWN));
	}

	/**
	 * An issuer URI carrying a trailing slash is the same issuer, and must not produce a
	 * discovery URL with a double slash that no authorization server answers.
	 */
	@Test
	void buildsTheDiscoveryUrlWhateverTheIssuerTrailingSlash() {
		this.environment.withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://localhost:9090/");

		assertThat(GatewayIssuers.from(this.environment)).containsValue("http://localhost:9090" + WELL_KNOWN);
	}

}
