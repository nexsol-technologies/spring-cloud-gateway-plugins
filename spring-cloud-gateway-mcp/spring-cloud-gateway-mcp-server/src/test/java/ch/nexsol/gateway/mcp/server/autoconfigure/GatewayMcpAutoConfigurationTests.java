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

package ch.nexsol.gateway.mcp.server.autoconfigure;

import java.util.List;

import ch.nexsol.gateway.commons.routes.RouteTester;
import ch.nexsol.gateway.commons.security.SecuredPaths;
import ch.nexsol.gateway.mcp.server.metrics.MetricsTools;
import ch.nexsol.gateway.mcp.server.pentest.PentestTools;
import ch.nexsol.gateway.mcp.server.pentest.PentestWriteTools;
import ch.nexsol.gateway.mcp.server.prompts.GatewayPrompts;
import ch.nexsol.gateway.mcp.server.resources.GatewayResources;
import ch.nexsol.gateway.mcp.server.routes.RouteMatchTools;
import ch.nexsol.gateway.mcp.server.routes.RouteTools;
import ch.nexsol.gateway.mcp.server.routes.RouteWriteTools;
import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import ch.nexsol.gateway.pentest.core.report.ReportService;
import ch.nexsol.gateway.pentest.core.store.FindingStore;
import ch.nexsol.gateway.pentest.core.store.InMemoryFindingStore;
import ch.nexsol.gateway.pentest.passive.score.RouteScoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerSseWebFluxAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStatelessWebFluxAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.cloud.gateway.route.InMemoryRouteDefinitionRepository;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMcpAutoConfigurationTests {

	private static final String ENABLED = "spring.cloud.gateway.server.webflux.mcp.enabled=true";

	// The Spring AI server auto-configurations are in the runner for the properties beans
	// they register, which is where the plugin reads the endpoints of the protocol in
	// force.
	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(GatewayMcpAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, McpServerStatelessAutoConfiguration.class,
				McpServerStatelessWebFluxAutoConfiguration.class, McpServerAutoConfiguration.class,
				McpServerStreamableHttpWebFluxAutoConfiguration.class, McpServerSseWebFluxAutoConfiguration.class));

	@Test
	void registersNothingWhileThePluginIsOff() {
		this.runner.run((context) -> {
			assertThat(context).doesNotHaveBean(RouteTools.class);
			assertThat(context).doesNotHaveBean(GatewayPrompts.class);
			assertThat(context).doesNotHaveBean(SecuredPaths.class);
		});
	}

	@Test
	void registersTheRouteToolsThePromptsAndTheSecuredEndpoint() {
		this.runner.withPropertyValues(ENABLED, "spring.ai.mcp.server.protocol=STATELESS").run((context) -> {
			assertThat(context).hasSingleBean(RouteTools.class);
			assertThat(context).hasSingleBean(GatewayPrompts.class);
			assertThat(context).hasSingleBean(RouteMatchTools.class);
			assertThat(context).hasSingleBean(RouteTester.class);
			assertThat(context).hasSingleBean(GatewayResources.class);
			assertThat(context).doesNotHaveBean(RouteWriteTools.class);
			SecuredPaths paths = context.getBean(SecuredPaths.class);
			assertThat(paths.writePaths()).containsExactly("/mcp");
			assertThat(paths.csrfExemptPaths()).containsExactly("/mcp");
			assertThat(context).doesNotHaveBean(MetricsTools.class);
			assertThat(context).doesNotHaveBean(PentestTools.class);
		});
	}

	@Test
	void declaresTheEndpointTheServerListensOnHoweverItIsSpelled() {
		this.runner
			.withPropertyValues(ENABLED, "spring.ai.mcp.server.protocol=STATELESS",
					"spring.ai.mcp.server.streamable-http.mcpEndpoint=/agent")
			.run((context) -> assertThat(context.getBean(SecuredPaths.class).writePaths()).containsExactly("/agent"));
	}

	@Test
	void declaresBothEndpointsOfTheSseProtocol() {
		this.runner.withPropertyValues(ENABLED, "spring.ai.mcp.server.protocol=SSE")
			.run((context) -> assertThat(context.getBean(SecuredPaths.class).writePaths()).containsExactly("/sse",
					"/mcp/message"));
	}

	@Test
	void declaresTheEndpointOfTheStreamableProtocol() {
		this.runner.withPropertyValues(ENABLED, "spring.ai.mcp.server.protocol=STREAMABLE")
			.run((context) -> assertThat(context.getBean(SecuredPaths.class).writePaths()).containsExactly("/mcp"));
	}

	@Test
	void leavesTheSecuredPathsToTheApplicationWhenItDeclaresThem() {
		this.runner.withPropertyValues(ENABLED)
			.withBean("gatewayMcpSecuredPaths", SecuredPaths.class, () -> SecuredPaths.open("/mcp"))
			.run((context) -> {
				assertThat(context).hasSingleBean(SecuredPaths.class);
				assertThat(context.getBean(SecuredPaths.class).openPaths()).containsExactly("/mcp");
			});
	}

	@Test
	void registersTheWriteToolsOnlyWhenWritingIsEnabled() {
		this.runner.withPropertyValues(ENABLED, "spring.cloud.gateway.server.webflux.mcp.write-enabled=true")
			.withBean(RouteDefinitionWriter.class, InMemoryRouteDefinitionRepository::new)
			.withUserConfiguration(PassiveScanScaffolding.class)
			.run((context) -> {
				assertThat(context).hasSingleBean(RouteWriteTools.class);
				assertThat(context).hasSingleBean(PentestWriteTools.class);
			});
	}

	@Test
	void leavesTheRouteTesterToTheConsoleWhenItDeclaresOne() {
		this.runner.withPropertyValues(ENABLED)
			.withBean("routeTester", RouteTester.class, () -> new RouteTester(null, null, null))
			.run((context) -> assertThat(context).hasSingleBean(RouteTester.class));
	}

	@Test
	void registersTheMetricsToolsOnceARouteSourceIsPresent() {
		this.runner.withPropertyValues(ENABLED)
			.withUserConfiguration(MetricsScaffolding.class)
			.run((context) -> assertThat(context).hasSingleBean(MetricsTools.class));
	}

	@Test
	void registersThePentestToolsOnceTheAnalyserIsWired() {
		this.runner.withPropertyValues(ENABLED)
			.withUserConfiguration(PassiveScanScaffolding.class)
			.run((context) -> assertThat(context).hasSingleBean(PentestTools.class));
	}

	@Configuration(proxyBeanMethods = false)
	static class MetricsScaffolding {

		@Bean
		RouteMetricsSource routeMetricsSource() {
			return () -> Mono.just(RouteMetricsSnapshot.empty("test"));
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class PassiveScanScaffolding {

		@Bean
		FindingStore findingStore() {
			return new InMemoryFindingStore(10);
		}

		@Bean
		ReportService reportService() {
			return new ReportService(new ObjectMapper().findAndRegisterModules());
		}

		@Bean
		RouteScoreService routeScoreService(FindingStore store) {
			return new RouteScoreService(List.of(), store);
		}

	}

}
