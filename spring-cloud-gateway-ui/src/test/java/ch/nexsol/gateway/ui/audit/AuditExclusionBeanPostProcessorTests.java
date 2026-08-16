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

package ch.nexsol.gateway.ui.audit;

import java.util.List;

import ch.nexsol.gateway.audit.AuditProperties;
import ch.nexsol.gateway.audit.autoconfigure.AuditAutoConfiguration;
import ch.nexsol.gateway.commons.security.SecuredPaths;
import ch.nexsol.gateway.ui.autoconfigure.GatewayUiAutoConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the paths the console serves are kept out of the global auditing web filter.
 */
class AuditExclusionBeanPostProcessorTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
				GatewayUiAutoConfiguration.class, AuditAutoConfiguration.class));

	@Test
	void excludesThePathsTheActiveViewsServe() {
		this.runner.run((context) -> assertThat(excludedPaths(context)).contains("/ui", "/ui/audit", "/ui/audit/events",
				"/js/gateway-audit.js", "/css/gateway-ui.css"));
	}

	@Test
	void keepsWhatTheApplicationExcludedAndAddsEachPathOnce() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.audit.web-filter.exclude-paths=/ui")
			.run((context) -> assertThat(excludedPaths(context)).containsOnlyOnce("/ui").contains("/ui/audit"));
	}

	@Test
	void excludesWhatAnotherPluginDeclaredOpenOrGoverned() {
		this.runner.withUserConfiguration(ContributingPluginConfiguration.class)
			.run((context) -> assertThat(excludedPaths(context)).contains("/plugin/browsed", "/plugin/polled"));
	}

	@Test
	void keepsTheApiThatChangesTheGatewayInTheAuditTrail() {
		// Who created a route, and when, is the very thing an audit trail is kept for.
		this.runner.withUserConfiguration(ContributingPluginConfiguration.class)
			.run((context) -> assertThat(excludedPaths(context)).doesNotContain("/plugin/routes"));
	}

	@Test
	void excludesThePageOverTheSameRoutes() {
		// A view is console traffic, fragment by fragment, whatever it lets an operator
		// do: leaving it in would drown the API calls above.
		this.runner.withUserConfiguration(ContributingPluginConfiguration.class)
			.run((context) -> assertThat(excludedPaths(context)).contains("/plugin/routes-page"));
	}

	@Test
	void excludesNothingWhenTheConsoleIsNotThere() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class))
			.run((context) -> assertThat(excludedPaths(context)).isEmpty());
	}

	private static List<String> excludedPaths(AssertableApplicationContext context) {
		return context.getBean(AuditProperties.class).getWebFilter().getExcludePaths();
	}

	/**
	 * A plugin declaring one path of each kind, as the OpenAPI hub, the discovery metrics
	 * and the route management do.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ContributingPluginConfiguration {

		@Bean
		SecuredPaths browsedPaths() {
			return SecuredPaths.governed("/plugin/browsed");
		}

		@Bean
		SecuredPaths polledPaths() {
			return SecuredPaths.open("/plugin/polled");
		}

		@Bean
		SecuredPaths routeManagementPaths() {
			return SecuredPaths.api("/plugin/routes");
		}

		@Bean
		SecuredPaths routeManagementViewPaths() {
			return SecuredPaths.write("/plugin/routes-page");
		}

	}

}
