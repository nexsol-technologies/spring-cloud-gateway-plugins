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

package ch.nexsol.gateway.mcp.server;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Derives the settings of the Spring AI MCP server from the plugin's own switch,
 * {@code spring.cloud.gateway.server.webflux.mcp.enabled}.
 * <p>
 * The defaults sit behind every other property source, so a
 * {@code spring.ai.mcp.server.*} value the application sets wins. Two of them matter.
 * Spring AI enables its server by default: without this, every gateway carrying the jar
 * with the plugin off would serve an unauthenticated, empty {@code /mcp}. And a server of
 * type {@code SYNC} silently ignores every tool declared with a reactive return type,
 * which is all of them here.
 */
public class GatewayMcpEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String ENABLED = "spring.cloud.gateway.server.webflux.mcp.enabled";

	static final String PROPERTY_SOURCE = "gatewayMcpDefaults";

	static final String INSTRUCTIONS = "Read-only view of a Spring Cloud Gateway: its routes and the source each "
			+ "came from, the filter and predicate factories available to write one, and, when the plugins are "
			+ "present, the traffic per route and the OWASP findings observed on the live traffic. "
			+ "Start with list_routes.";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		boolean enabled = environment.getProperty(ENABLED, Boolean.class, false);
		Map<String, Object> defaults = Map.of("spring.ai.mcp.server.enabled", enabled, "spring.ai.mcp.server.protocol",
				"STATELESS", "spring.ai.mcp.server.type", "ASYNC", "spring.ai.mcp.server.name",
				"${spring.application.name:spring-cloud-gateway}", "spring.ai.mcp.server.instructions", INSTRUCTIONS);
		environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE, defaults));
	}

}
