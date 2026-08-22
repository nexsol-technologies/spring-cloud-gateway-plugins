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

package ch.nexsol.service.sample.c;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the contract of this service says about the token it asks for &mdash; the shape
 * the OpenAPI hub rewrites and the console renders.
 */
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9090")
@AutoConfigureWebTestClient(timeout = "300000")
class ResourceServerContractTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldDeclareAnOpenIdConnectSchemePointingAtItsOwnIssuer() {
		// The type is what the hub keys on: it rewrites openIdConnect schemes and leaves
		// every other kind alone.
		contract().jsonPath("$.components.securitySchemes.bearer-oidc.type")
			.isEqualTo("openIdConnect")
			.jsonPath("$.components.securitySchemes.bearer-oidc.openIdConnectUrl")
			.isEqualTo("http://localhost:9090/.well-known/openid-configuration");
	}

	@Test
	void shouldNotDeclareABearerScheme() {
		// A bearer scheme carries no scope, so nothing could be ticked in the console.
		contract().jsonPath("$.components.securitySchemes.bearer-oidc.scheme").doesNotExist();
	}

	@Test
	void shouldCarryTheScopesOnTheRequirement() {
		// Where the console reads the scopes to tick from: an openIdConnect scheme
		// declares none of its own.
		contract().jsonPath("$.security[0].bearer-oidc")
			.value((List<String> scopes) -> assertThat(scopes).containsExactly("openid", "profile", "email"));
	}

	@Test
	void shouldStillDescribeItsOperation() {
		// The bean replaces nothing: SpringDoc adds the scanned paths to it.
		contract().jsonPath("$.paths./service-c/data.get").exists();
	}

	private WebTestClient.BodyContentSpec contract() {
		return this.webTestClient.get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody();
	}

}
