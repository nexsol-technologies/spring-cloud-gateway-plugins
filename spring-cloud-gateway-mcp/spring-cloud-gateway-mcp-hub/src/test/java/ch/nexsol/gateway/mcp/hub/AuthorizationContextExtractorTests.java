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

package ch.nexsol.gateway.mcp.hub;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationContextExtractorTests {

	private final AuthorizationContextExtractor extractor = new AuthorizationContextExtractor();

	@Test
	void carriesTheBearerTokenOfTheCaller() {
		assertThat(extract("Bearer agent-token").get(ApiToolInvoker.AUTHORIZATION)).isEqualTo("Bearer agent-token");
		assertThat(extract("bearer agent-token").get(ApiToolInvoker.AUTHORIZATION)).isEqualTo("bearer agent-token");
	}

	@Test
	void keepsTheBasicCredentialsOfTheEndpointToItself() {
		assertThat(extract("Basic YWRtaW46bWNwLXNlY3JldA==")).isSameAs(McpTransportContext.EMPTY);
	}

	@Test
	void carriesNothingForAnAnonymousCaller() {
		assertThat(this.extractor.extract(MockServerRequest.builder().build())).isSameAs(McpTransportContext.EMPTY);
	}

	private McpTransportContext extract(String authorization) {
		return this.extractor
			.extract(MockServerRequest.builder().header(HttpHeaders.AUTHORIZATION, authorization).build());
	}

}
