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

package ch.nexsol.gateway.mcp.server.prompts;

import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayPromptsTests {

	private final GatewayPrompts prompts = new GatewayPrompts();

	@Test
	void theDiagnosisNamesTheRouteInEveryToolCallItAsksFor() {
		StepVerifier.create(this.prompts.diagnoseRoute("books")).assertNext((result) -> {
			assertThat(result.description()).contains("books");
			assertThat(result.messages()).hasSize(1);
			assertThat(result.messages().get(0).role()).isEqualTo(Role.USER);
			String text = ((TextContent) result.messages().get(0).content()).text();
			assertThat(text).contains("get_route with routeId 'books'").contains("list_findings");
		}).verifyComplete();
	}

	@Test
	void theReviewWalksThroughTheFindingTools() {
		StepVerifier.create(this.prompts.securityReview()).assertNext((result) -> {
			String text = ((TextContent) result.messages().get(0).content()).text();
			assertThat(text).contains("finding_summary").contains("route_security_scores").contains("scan_coverage");
		}).verifyComplete();
	}

}
