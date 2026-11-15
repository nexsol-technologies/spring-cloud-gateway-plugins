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

package ch.nexsol.gateway.sample;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The console and the MCP endpoint on one gateway, each with a chain of its own: the
 * endpoint answers the user it is given here, and no longer the user of the console.
 */
@SpringBootTest(properties = { "eureka.client.enabled=false",
		"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false",
		"spring.cloud.gateway.server.webflux.mcp.security.user.name=agent",
		"spring.cloud.gateway.server.webflux.mcp.security.user.password=agent-secret" })
@AutoConfigureWebTestClient
class McpOwnChainTests {

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":1,"method":"tools/list"}""";

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldAnswerTheUserOfTheEndpoint() {
		call("agent", "agent-secret").expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.result.tools[?(@.name=='list_routes')]")
			.exists();
	}

	@Test
	void shouldNotAnswerTheUserOfTheConsole() {
		// superadmin signs into the console, over its form or over Basic. It is not a
		// user of the MCP endpoint.
		call("superadmin", "superadmin").expectStatus().isUnauthorized();
	}

	private WebTestClient.ResponseSpec call(String user, String password) {
		return this.webTestClient.post()
			.uri("/mcp")
			.headers((headers) -> headers.setBasicAuth(user, password))
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.bodyValue(TOOLS_LIST)
			.exchange();
	}

}
