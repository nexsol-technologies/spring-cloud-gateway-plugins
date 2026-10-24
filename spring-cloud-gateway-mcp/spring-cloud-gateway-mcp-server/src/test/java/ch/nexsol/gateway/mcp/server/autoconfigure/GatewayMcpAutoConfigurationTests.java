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

import ch.nexsol.gateway.commons.security.SecuredPaths;
import ch.nexsol.gateway.mcp.server.metrics.MetricsTools;
import ch.nexsol.gateway.mcp.server.pentest.PentestTools;
import ch.nexsol.gateway.mcp.server.prompts.GatewayPrompts;
import ch.nexsol.gateway.mcp.server.routes.RouteTools;
import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import ch.nexsol.gateway.pentest.core.report.ReportService;
import ch.nexsol.gateway.pentest.core.store.FindingStore;
import ch.nexsol.gateway.pentest.core.store.InMemoryFindingStore;
import ch.nexsol.gateway.pentest.passive.score.RouteScoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMcpAutoConfigurationTests {

	private static final String ENABLED = "spring.cloud.gateway.server.webflux.mcp.enabled=true";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(GatewayMcpAutoConfiguration.class));

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
		this.runner.withPropertyValues(ENABLED).run((context) -> {
			assertThat(context).hasSingleBean(RouteTools.class);
			assertThat(context).hasSingleBean(GatewayPrompts.class);
			SecuredPaths paths = context.getBean(SecuredPaths.class);
			assertThat(paths.writePaths()).containsExactly("/mcp");
			assertThat(paths.csrfExemptPaths()).containsExactly("/mcp");
			assertThat(context).doesNotHaveBean(MetricsTools.class);
			assertThat(context).doesNotHaveBean(PentestTools.class);
		});
	}

	@Test
	void declaresTheEndpointTheServerListensOn() {
		this.runner.withPropertyValues(ENABLED, "spring.ai.mcp.server.streamable-http.mcp-endpoint=/agent")
			.run((context) -> assertThat(context.getBean(SecuredPaths.class).writePaths()).containsExactly("/agent"));
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
