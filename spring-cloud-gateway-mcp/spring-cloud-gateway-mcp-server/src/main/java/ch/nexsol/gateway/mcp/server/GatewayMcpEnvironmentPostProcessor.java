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
import java.util.stream.Stream;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;

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
 * <p>
 * The switch is read when the server setting is read, not once here: a property source
 * added after the environment is post-processed — the legacy bootstrap of a Config Server
 * client adds its own then — must drive the server the same way it drives the tools. And
 * it is read as the auto-configuration reads it, the string {@code true} in any case: a
 * {@code yes} that a Boolean conversion would accept switches neither half on.
 */
public class GatewayMcpEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String ENABLED = "spring.cloud.gateway.server.webflux.mcp.enabled";

	static final String SPRING_AI_ENABLED = "spring.ai.mcp.server.enabled";

	static final String PROPERTY_SOURCE = "gatewayMcpDefaults";

	static final String INSTRUCTIONS = "Read-only view of a Spring Cloud Gateway: its routes and the source each "
			+ "came from, the filter and predicate factories available to write one, and, when the plugins are "
			+ "present, the traffic per route and the OWASP findings observed on the live traffic. "
			+ "Start with list_routes.";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		environment.getPropertySources().addLast(new Defaults(environment));
	}

	static boolean enabled(Environment environment) {
		return "true".equalsIgnoreCase(environment.getProperty(ENABLED));
	}

	/**
	 * The defaults, the server switch among them read live from the environment.
	 * <p>
	 * The environment is held in a field, not as the source: Spring Boot flattens a
	 * property source whose source is a {@code ConfigurableEnvironment} into that
	 * environment's own sources, this one included, and iterates them without end.
	 */
	static final class Defaults extends EnumerablePropertySource<Map<String, Object>> {

		private static final Map<String, Object> FIXED = Map.of("spring.ai.mcp.server.protocol", "STATELESS",
				"spring.ai.mcp.server.type", "ASYNC", "spring.ai.mcp.server.name",
				"${spring.application.name:spring-cloud-gateway}", "spring.ai.mcp.server.instructions", INSTRUCTIONS);

		private static final String[] NAMES = Stream.concat(FIXED.keySet().stream(), Stream.of(SPRING_AI_ENABLED))
			.toArray(String[]::new);

		private final Environment environment;

		Defaults(Environment environment) {
			super(PROPERTY_SOURCE, FIXED);
			this.environment = environment;
		}

		@Override
		public String[] getPropertyNames() {
			return NAMES;
		}

		@Override
		public Object getProperty(String name) {
			if (SPRING_AI_ENABLED.equals(name)) {
				return Boolean.toString(enabled(this.environment));
			}
			return FIXED.get(name);
		}

	}

}
