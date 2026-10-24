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

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;

/**
 * The prompts the server offers: workflows over the tools, written once, so a client asks
 * for a diagnosis or a review in one step. Each names the tools to call and says what to
 * do when a group of them is absent.
 */
public class GatewayPrompts {

	@McpPrompt(name = "diagnose-route",
			description = "Everything the gateway knows about one route: its definition, its traffic, the findings "
					+ "raised on it and its security score.")
	public Mono<GetPromptResult> diagnoseRoute(@McpArg(name = "routeId",
			description = "The id of the route to diagnose", required = true) String routeId) {
		String text = """
				Diagnose the route '%1$s' of this gateway.
				1. Call get_route with routeId '%1$s' and describe what it does: the requests it matches, the filters it applies and the service it targets.
				2. If route_traffic is available, call it and set this route's requests, latency and error rates against the other routes.
				3. If list_findings is available, call it with routeId '%1$s' and summarise the findings by severity; read its line in route_security_scores.
				4. Conclude with what is wrong, if anything, and the change to the route definition that would fix it.
				"""
			.formatted(routeId);
		return Mono.just(prompt("Diagnosis of route " + routeId, text));
	}

	@McpPrompt(name = "security-review",
			description = "A review of the gateway's security posture from the passive scan findings and the route "
					+ "scores.")
	public Mono<GetPromptResult> securityReview() {
		String text = """
				Review the security posture of this gateway.
				1. Call finding_summary, then list_findings for the CRITICAL and HIGH severities.
				2. Call route_security_scores and name the routes with the lowest scores.
				3. Call scan_coverage and say which OWASP categories this review could not observe.
				4. Report the findings grouped by route, worst first, each with its remediation, then the categories left unreviewed.
				If the finding tools are absent, say that the passive analyser is not running and stop there.
				""";
		return Mono.just(prompt("Security review of the gateway", text));
	}

	private static GetPromptResult prompt(String description, String text) {
		return new GetPromptResult(description, List.of(new PromptMessage(Role.USER, new TextContent(text))));
	}

}
