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

import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMcpEnvironmentPostProcessorTests {

	private static final String SERVER_ENABLED = GatewayMcpEnvironmentPostProcessor.SPRING_AI_ENABLED;

	private final GatewayMcpEnvironmentPostProcessor processor = new GatewayMcpEnvironmentPostProcessor();

	@Test
	void keepsTheSpringAiServerOffWhileThePluginIsOff() {
		MockEnvironment environment = new MockEnvironment();
		this.processor.postProcessEnvironment(environment, new SpringApplication());
		assertThat(environment.getProperty(SERVER_ENABLED, Boolean.class)).isFalse();
	}

	@Test
	void switchesTheSpringAiServerOnWithThePluginInTheModeTheToolsNeed() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty(GatewayMcpEnvironmentPostProcessor.ENABLED, "true")
			.withProperty("spring.application.name", "edge");
		this.processor.postProcessEnvironment(environment, new SpringApplication());
		assertThat(environment.getProperty(SERVER_ENABLED, Boolean.class)).isTrue();
		assertThat(environment.getProperty("spring.ai.mcp.server.protocol")).isEqualTo("STATELESS");
		assertThat(environment.getProperty("spring.ai.mcp.server.type")).isEqualTo("ASYNC");
		assertThat(environment.getProperty("spring.ai.mcp.server.name")).isEqualTo("edge");
		assertThat(environment.getProperty("spring.ai.mcp.server.instructions")).contains("list_routes");
	}

	@Test
	void readsTheSwitchTheWayTheAutoConfigurationDoes() {
		// @ConditionalOnProperty compares the text 'true', in any case; a 'yes' or a '1'
		// that a Boolean conversion would accept must switch neither half on.
		MockEnvironment environment = new MockEnvironment().withProperty(GatewayMcpEnvironmentPostProcessor.ENABLED,
				"yes");
		this.processor.postProcessEnvironment(environment, new SpringApplication());
		assertThat(environment.getProperty(SERVER_ENABLED, Boolean.class)).isFalse();
		environment.setProperty(GatewayMcpEnvironmentPostProcessor.ENABLED, "TRUE");
		assertThat(environment.getProperty(SERVER_ENABLED, Boolean.class)).isTrue();
	}

	@Test
	void followsASwitchAddedAfterItRan() {
		MockEnvironment environment = new MockEnvironment();
		this.processor.postProcessEnvironment(environment, new SpringApplication());
		environment.setProperty(GatewayMcpEnvironmentPostProcessor.ENABLED, "true");
		assertThat(environment.getProperty(SERVER_ENABLED, Boolean.class)).isTrue();
	}

	@Test
	void namesTheServerAfterTheGatewayWhenTheApplicationHasNoName() {
		MockEnvironment environment = new MockEnvironment().withProperty(GatewayMcpEnvironmentPostProcessor.ENABLED,
				"true");
		this.processor.postProcessEnvironment(environment, new SpringApplication());
		assertThat(environment.getProperty("spring.ai.mcp.server.name")).isEqualTo("spring-cloud-gateway");
	}

	@Test
	void leavesTheValuesTheApplicationSetsAlone() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty(GatewayMcpEnvironmentPostProcessor.ENABLED, "true")
			.withProperty("spring.ai.mcp.server.name", "my-gateway")
			.withProperty(SERVER_ENABLED, "false");
		this.processor.postProcessEnvironment(environment, new SpringApplication());
		assertThat(environment.getProperty("spring.ai.mcp.server.name")).isEqualTo("my-gateway");
		assertThat(environment.getProperty(SERVER_ENABLED, Boolean.class)).isFalse();
		assertThat(environment.getProperty("spring.ai.mcp.server.type")).isEqualTo("ASYNC");
	}

}
