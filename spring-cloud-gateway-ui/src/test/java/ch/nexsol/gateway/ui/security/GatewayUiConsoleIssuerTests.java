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

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console can validate its Bearer tokens against an issuer of its own, rather than
 * the one the gateway validates the traffic it routes against:
 * {@code spring.security.oauth2.resourceserver} holds a single issuer for the whole
 * application, so a gateway answering to one authorization server cannot have its console
 * answer to another through it.
 * <p>
 * The issuer here answers nothing, which is the point of the test: naming it must not
 * make the gateway wait on the provider to come up. The keys are fetched on the first
 * token that arrives, so this context starts and this chain builds against an issuer that
 * will never reply.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret",
		"spring.cloud.gateway.server.webflux.ui.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:59999/realms/absent" })
@AutoConfigureWebTestClient(timeout = "300000")
class GatewayUiConsoleIssuerTests {

	@Autowired
	GatewayUiSecurityProperties properties;

	@Autowired
	SecurityWebFilterChain gatewayUiSecurityWebFilterChain;

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldReadTheIssuerOfTheConsoleApartFromTheOneOfTheGateway() {
		assertThat(this.properties.getOauth2().getResourceserver().getJwt().getIssuerUri())
			.isEqualTo("http://localhost:59999/realms/absent");
	}

	@Test
	void shouldBuildTheChainWithoutWaitingOnTheProvider() {
		/*
		 * Reaching this at all is the assertion: an issuer resolved at start-up would
		 * have failed the context against a provider that answers nothing.
		 */
		assertThat(this.gatewayUiSecurityWebFilterChain).isNotNull();
	}

	@Test
	void shouldStillServeTheLoginPage() {
		this.webTestClient.get().uri("/ui/login").exchange().expectStatus().isOk();
	}

}
