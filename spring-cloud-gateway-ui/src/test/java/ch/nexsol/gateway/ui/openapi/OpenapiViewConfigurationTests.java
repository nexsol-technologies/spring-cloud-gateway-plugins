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

package ch.nexsol.gateway.ui.openapi;

import java.util.List;

import ch.nexsol.gateway.openapi.hub.SpringDocOpenapiRoutes;
import ch.nexsol.gateway.ui.autoconfigure.GatewayUiAutoConfiguration;
import ch.nexsol.gateway.ui.nav.NavItem;
import ch.nexsol.gateway.ui.security.UiSecuredPaths;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the OpenAPI view of the shell, which only lights up when the OpenAPI hub
 * plugin is present and enabled.
 */
class OpenapiViewConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(GatewayUiAutoConfiguration.class));

	@Test
	void viewIsAbsentWhenTheHubIsNotEnabled() {
		this.runner.run((context) -> {
			assertThat(context).doesNotHaveBean(OpenapiViewController.class);
			assertThat(navIds(context)).doesNotContain("openapi");
		});
	}

	@Test
	void viewIsAbsentWhenTheHubIsNotOnTheClasspath() {
		this.runner.withClassLoader(new FilteredClassLoader(SpringDocOpenapiRoutes.class))
			.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true")
			.run((context) -> assertThat(context).doesNotHaveBean(OpenapiViewController.class));
	}

	@Test
	void viewIsRegisteredWhenTheHubIsEnabled() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true")
			.run((context) -> {
				assertThat(context).hasSingleBean(OpenapiViewController.class);
				assertThat(navIds(context)).contains("openapi");
				assertThat(securedPaths(context)).contains("/ui/openapi", "/js/scalar.standalone.js",
						"/js/gateway-openapi.js");
			});
	}

	@Test
	void springDocPathIsHonoured() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true",
					"springdoc.api-docs.path=/docs")
			.run((context) -> {
				OpenapiViewController controller = context.getBean(OpenapiViewController.class);
				Model model = new ConcurrentModel();
				assertThat(controller.page(model)).isEqualTo("dashboard/openapi");
				assertThat(model.getAttribute("openapiDocumentUrl")).isEqualTo("/docs");
				assertThat(model.getAttribute("openapiConfigUrl")).isEqualTo("/docs/swagger-config");
			});
	}

	@Test
	void extensionLabelsReachThePageInTheOrderTheyAreDeclared() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true",
					"spring.cloud.gateway.server.webflux.ui.openapi.extensions.x-roles=Required roles",
					"spring.cloud.gateway.server.webflux.ui.openapi.extensions.x-from-application-version=Since")
			.run((context) -> {
				Model model = new ConcurrentModel();
				context.getBean(OpenapiViewController.class).page(model);
				assertThat(model.getAttribute("openapiExtensionLabels"))
					.isEqualTo("{\"x-roles\":\"Required roles\",\"x-from-application-version\":\"Since\"}");
			});
	}

	@Test
	void pageCarriesAnEmptyMappingWhenNoExtensionIsDeclared() {
		// The page parses the attribute whether or not anything was declared, so a view
		// configured with no extension renders rather than breaking on it.
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true")
			.run((context) -> {
				Model model = new ConcurrentModel();
				context.getBean(OpenapiViewController.class).page(model);
				assertThat(model.getAttribute("openapiExtensionLabels")).isEqualTo("{}");
			});
	}

	@Test
	void tryItIsOfferedByDefault() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true")
			.run((context) -> {
				Model model = new ConcurrentModel();
				context.getBean(OpenapiViewController.class).page(model);
				assertThat(model.getAttribute("openapiTryIt")).isEqualTo(true);
			});
	}

	@Test
	void tryItIsWithheldWhenItIsTurnedOff() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true",
					"spring.cloud.gateway.server.webflux.ui.openapi.try-it=false")
			.run((context) -> {
				Model model = new ConcurrentModel();
				context.getBean(OpenapiViewController.class).page(model);
				assertThat(model.getAttribute("openapiTryIt")).isEqualTo(false);
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
