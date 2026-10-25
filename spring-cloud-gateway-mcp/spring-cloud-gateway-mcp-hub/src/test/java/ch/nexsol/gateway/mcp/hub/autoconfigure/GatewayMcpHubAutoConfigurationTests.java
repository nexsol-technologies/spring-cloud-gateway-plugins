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

package ch.nexsol.gateway.mcp.hub.autoconfigure;

import ch.nexsol.gateway.mcp.hub.HubToolRegistrar;
import ch.nexsol.gateway.openapi.HubOpenapiProperties;
import org.junit.jupiter.api.Test;

import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStatelessWebFluxAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMcpHubAutoConfigurationTests {

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessAutoConfiguration.class, McpServerStatelessWebFluxAutoConfiguration.class,
				GatewayMcpHubTransportAutoConfiguration.class, GatewayMcpHubAutoConfiguration.class))
		.withUserConfiguration(HubProperties.class)
		.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.gateway-uri=http://gateway:8181",
				"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC");

	@Test
	void registersNothingWhileTheBridgeIsOff() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true")
			.run((context) -> assertThat(context).doesNotHaveBean(HubToolRegistrar.class));
	}

	@Test
	void registersNothingWithoutTheHub() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.hub.enabled=true")
			.run((context) -> assertThat(context).doesNotHaveBean(HubToolRegistrar.class));
	}

	@Test
	void registersTheBridgeOverTheStatelessServerOnceBothAreOn() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.hub.enabled=true",
					"spring.cloud.gateway.server.webflux.hub-openapi.enabled=true")
			.run((context) -> {
				assertThat(context).hasSingleBean(HubToolRegistrar.class);
				assertThat(context).hasBean("gatewayMcpHubWebClient");
			});
	}

	@Test
	void staysOffOverAServerThatIsNotStateless() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.hub.enabled=true",
					"spring.cloud.gateway.server.webflux.hub-openapi.enabled=true",
					"spring.ai.mcp.server.protocol=STREAMABLE")
			.run((context) -> assertThat(context).doesNotHaveBean(HubToolRegistrar.class));
	}

	@Configuration(proxyBeanMethods = false)
	static class HubProperties {

		@Bean
		HubOpenapiProperties hubOpenapiProperties() {
			return new HubOpenapiProperties();
		}

	}

}
