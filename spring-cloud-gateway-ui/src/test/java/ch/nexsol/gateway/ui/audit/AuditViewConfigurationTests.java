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

import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.ui.autoconfigure.GatewayUiAutoConfiguration;
import ch.nexsol.gateway.ui.nav.NavItem;
import ch.nexsol.gateway.ui.security.UiSecuredPaths;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the audit view of the shell, which only lights up when the audit plugin is
 * present and enabled.
 */
class AuditViewConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(GatewayUiAutoConfiguration.class));

	@Test
	void viewIsAbsentWhenTheAuditPluginIsDisabled() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.audit.enabled=false").run((context) -> {
			assertThat(context).doesNotHaveBean(AuditTailController.class);
			assertThat(context).doesNotHaveBean(AuditTailBuffer.class);
			assertThat(context).doesNotHaveBean(AuditOverviewContribution.class);
			assertThat(navIds(context)).doesNotContain("audit");
			assertThat(securedPaths(context)).doesNotContain("/ui/audit");
		});
	}

	@Test
	void viewIsAbsentWhenTheAuditPluginIsNotOnTheClasspath() {
		this.runner.withClassLoader(new FilteredClassLoader(AuditEventPublisher.class))
			.run((context) -> assertThat(context).doesNotHaveBean(AuditTailController.class));
	}

	@Test
	void viewIsRegisteredByDefault() {
		this.runner.run((context) -> {
			assertThat(context).hasSingleBean(AuditTailController.class);
			assertThat(navIds(context)).contains("audit");
			assertThat(securedPaths(context)).contains("/ui/audit", "/ui/audit/events", "/js/gateway-audit.js");
		});
	}

	@Test
	void viewIsRegisteredWhenTheAuditPluginIsExplicitlyEnabled() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.audit.enabled=true").run((context) -> {
			assertThat(context).hasSingleBean(AuditTailController.class);
			assertThat(navIds(context)).contains("audit");
		});
	}

	private static List<String> navIds(AssertableApplicationContext context) {
		return context.getBeansOfType(NavItem.class).values().stream().map(NavItem::id).toList();
	}

	private static List<String> securedPaths(AssertableApplicationContext context) {
		return context.getBeansOfType(UiSecuredPaths.class)
			.values()
			.stream()
			.flatMap((contribution) -> contribution.paths().stream())
			.toList();
	}

}
