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

package ch.nexsol.gateway.sample.filters;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code Authorization} filter on the two verdicts it can reach without any
 * backend: the request is rejected before it is ever forwarded.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class FiltersGatewayApplicationTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldRejectAnAnonymousCallOnAnAuthorizedRoute() {
		this.webTestClient.get().uri("/authorization/sample").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void shouldRejectAnAuthenticatedCallLackingTheAuthority() {
		this.webTestClient.get()
			.uri("/authorization/sample")
			.headers((headers) -> headers.setBasicAuth("admin", "admin"))
			.exchange()
			.expectStatus()
			.isForbidden();
	}

	@Test
	void shouldRejectEveryAccountOnARouteAskingForAnAuthorityNobodyHolds() {
		this.webTestClient.get()
			.uri("/authorization-ko/sample")
			.headers((headers) -> headers.setBasicAuth("user", "user"))
			.exchange()
			.expectStatus()
			.isForbidden();
	}

	/**
	 * The granted case cannot be asserted on its response, which depends on a backend the
	 * sample does not start. What it does assert is that the filter let the request
	 * through: it never answered 401 or 403 itself.
	 */
	@Test
	void shouldForwardACallCarryingTheAuthority() {
		this.webTestClient.get()
			.uri("/authorization/sample")
			.headers((headers) -> headers.setBasicAuth("user", "user"))
			.exchange()
			.expectStatus()
			.value((status) -> assertThat(status).isNotIn(401, 403));
	}

}
