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

package ch.nexsol.gateway.sample.secured;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the line this combination draws: a route flagged public is served without a
 * token, everything else is answered 401 before it is routed anywhere. The authorization
 * server the sample validates tokens against is not started, which is precisely why only
 * the tokenless verdicts are asserted.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class SecuredGatewayApplicationTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldServeAPublicRouteWithoutAToken() {
		this.webTestClient.get()
			.uri("/public/sample")
			.exchange()
			.expectStatus()
			.value((status) -> assertThat(status).isNotEqualTo(401));
	}

	@Test
	void shouldRejectTheSameBackendBehindARouteThatIsNotPublic() {
		this.webTestClient.get().uri("/private/sample").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void shouldRejectTheRoutesGuardedByTheAuthorizationTokenFilter() {
		this.webTestClient.get().uri("/secured/sample").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void shouldRejectTheRoutesGuardedByTheAuthorizationFilter() {
		this.webTestClient.get().uri("/secured-read/sample").exchange().expectStatus().isUnauthorized();
	}

}
