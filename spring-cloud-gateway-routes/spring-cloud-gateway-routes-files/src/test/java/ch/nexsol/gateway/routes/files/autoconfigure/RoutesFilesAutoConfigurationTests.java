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

package ch.nexsol.gateway.routes.files.autoconfigure;

import ch.nexsol.gateway.routes.files.FileRouteDefinitionLocator;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.route.RouteDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration tests wiring {@link RoutesFilesAutoConfiguration} end-to-end and
 * verifying the initial load performed by the lifecycle.
 */
class RoutesFilesAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(RoutesFilesAutoConfiguration.class));

	@Test
	void locatorIsAbsentWhenDisabled() {
		this.runner.run((context) -> assertThat(context).doesNotHaveBean(FileRouteDefinitionLocator.class));
	}

	@Test
	void locatorLoadsRoutesWhenEnabled() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.routes.files.enabled=true",
					"spring.cloud.gateway.routes.files.locations=classpath:routes/sample-routes.yaml")
			.run((context) -> {
				assertThat(context).hasSingleBean(FileRouteDefinitionLocator.class);
				FileRouteDefinitionLocator locator = context.getBean(FileRouteDefinitionLocator.class);
				locator.refresh().block();
				assertThat(locator.getRouteDefinitions().map(RouteDefinition::getId).collectList().block())
					.containsExactly("after_route");
			});
	}

}
