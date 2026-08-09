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

import java.util.Map;

import ch.nexsol.gateway.oauth2.filter.BaseWebClientTests;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext
@ActiveProfiles(profiles = "authorization-token")
class AuthorizationTokenGatewayFilterFactoryIntegrationTests extends BaseWebClientTests {

	private static final String token = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL25leHNvbC50ZWNoIiwic3ViIjoibmV4c29sLWFkbWluIiwiYXVkIjoiYXBpIiwiaWF0IjoxNzA3MDQyOTE3LCJleHAiOjE3MDcwNDM1MTcsImFhYSI6dHJ1ZSwiYXpwIjoibXktY2xpZW50IiwicmVhbG1fYWNjZXNzIjp7InJvbGVzIjpbIm9mZmxpbmVfYWNjZXNzIiwiYWRtaW4iLCJkZWZhdWx0LXJvbGVzLWV4YW1wbGUiLCJ1bWFfYXV0aG9yaXphdGlvbiIsInVzZXIiXX0sInJlc291cmNlX2FjY2VzcyI6eyJzcHJpbmctY2xpZW50LTEiOnsicm9sZXMiOlsicm9sZTEiXX0sInNwcmluZy1jbGllbnQtMiI6eyJyb2xlcyI6WyJyb2xlMiJdfX19.y1lAdAy11VpfUEiKPvij6rb-QeIX_0m7M5rCw8XrT9ZkDkeyPD_uxhgMIvSPvFI_SwT9PYS3wZ1RKSOBezaJresf7JYNBwvI1yHybNvtWRJQeJVLBwuvVlks02AvaXBIdq_d3ZsZBd9x_gzAQ5wCE31eAjb2kgdRFnU3NFvjtkuHDcdZufv_qrJkUIVKNJdPMrttv8_QvnyUE9j_Tjm7KAOBS-_tWaDxDcKB6nJwkmkpu_l2XH9ac1WAb15_orRyGulqsqW1hBWh9vmSvTBFOJQAfPqHXyx-k6oWPjj3regu7nxj8qilpxVa7uWxScuTAYpgd2NbKQJfFqtfQGo5GQ";

	@Test
	void validateAuthorizationTokenWorks() {
		this.testClient.get().uri("/authorization-token").headers((headers) -> {
			headers.set("Host", "www.validateauthorizationtoken.ch");
			headers.setBearerAuth(token);
		})
			.exchange()
			.expectBody(Map.class)
			.consumeWith((result) -> assertThat(result.getStatus()).isEqualTo(HttpStatus.OK));
	}

	@Test
	void validateAuthorizationTokenWorksWithAll() {
		this.testClient.get().uri("/authorization-token-all").headers((headers) -> {
			headers.set("Host", "www.validateauthorizationtoken.ch");
			headers.setBearerAuth(token);
		})
			.exchange()
			.expectBody(Map.class)
			.consumeWith((result) -> assertThat(result.getStatus()).isEqualTo(HttpStatus.OK));
	}

	@Test
	void validateAuthorizationTokenWorksWithBadIssuer() {
		this.testClient.get().uri("/authorization-token-with-bad-issuer").headers((headers) -> {
			headers.set("Host", "www.validateauthorizationtoken.ch");
			headers.setBearerAuth(token);
		})
			.exchange()
			.expectBody(Map.class)
			.consumeWith((result) -> assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void validateAuthorizationTokenWorksWithoutToken() {
		this.testClient.get()
			.uri("/authorization-token-with-bad-issuer")
			.headers((headers) -> headers.set("Host", "www.validateauthorizationtoken.ch"))
			.exchange()
			.expectStatus()
			.isUnauthorized();
	}

	@Test
	void validateAuthorizationTokenWorksWithoutTokenOnPublicRoute() {
		// The route is flagged public, so the missing token is not rejected: the request
		// reaches the routing filters and is answered by the unresolvable
		// `lb://testservice` uri. The 503 is what proves it was forwarded, not denied.
		this.testClient.get()
			.uri("/authorization-token-public")
			.headers((headers) -> headers.set("Host", "www.validateauthorizationtoken.ch"))
			.exchange()
			.expectStatus()
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void validateAuthorizationTokenWorksWithASingleMatchingRole() {
		// The token holds role1 but not __bad_role, so only ANY lets it through. The
		// 503 of the unresolvable `lb://testservice` uri proves the request was
		// forwarded: with the default ALL the filter would answer 403 instead.
		this.testClient.get().uri("/authorization-token-any-role").headers((headers) -> {
			headers.set("Host", "www.validateauthorizationtoken.ch");
			headers.setBearerAuth(token);
		}).exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void validateAuthorizationTokenWorksWithASingleMatchingGrantAccess() {
		// Same reasoning for `grant-accesses-match: ANY`: the first granted access is
		// never satisfied, so only ANY forwards the request.
		this.testClient.get().uri("/authorization-token-any-grant-access").headers((headers) -> {
			headers.set("Host", "www.validateauthorizationtoken.ch");
			headers.setBearerAuth(token);
		}).exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void validateAuthorizationTokenWorksWithBadGrantAccess() {
		this.testClient.get().uri("/authorization-token-with-bad-grant-access").headers((headers) -> {
			headers.set("Host", "www.validateauthorizationtoken.ch");
			headers.setBearerAuth(token);
		})
			.exchange()
			.expectBody(Map.class)
			.consumeWith((result) -> assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
	}

}
