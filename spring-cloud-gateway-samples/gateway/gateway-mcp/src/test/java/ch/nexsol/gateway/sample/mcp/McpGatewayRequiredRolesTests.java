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

package ch.nexsol.gateway.sample.mcp;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The sample asks for the {@code ADMIN} role on {@code /mcp}: the same user, holding
 * another role, is known and refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.cloud.gateway.server.webflux.mcp.security.user.roles=READER")
@AutoConfigureWebTestClient
class McpGatewayRequiredRolesTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void refusesAnAuthenticatedClientHoldingNoneOfTheRequiredRoles() {
		this.webTestClient.post()
			.uri("/mcp")
			.headers((headers) -> headers.setBasicAuth("admin", "admin"))
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.bodyValue("""
					{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
			.exchange()
			.expectStatus()
			.isForbidden();
	}

}
