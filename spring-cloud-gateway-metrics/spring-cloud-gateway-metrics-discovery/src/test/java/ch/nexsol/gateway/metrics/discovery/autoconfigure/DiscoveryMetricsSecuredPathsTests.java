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

package ch.nexsol.gateway.metrics.discovery.autoconfigure;

import ch.nexsol.gateway.commons.security.SecuredPaths;
import ch.nexsol.gateway.metrics.autoconfigure.MetricsAutoConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that this module tells whoever governs the paths of the gateway about the
 * endpoints it serves to the sibling instances, and that it tells it the truth: the paths
 * as configured, not the constants they default to.
 */
class DiscoveryMetricsSecuredPathsTests {

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
				MetricsAutoConfiguration.class, DiscoveryMetricsAutoConfiguration.class))
		.withBean(WebClient.Builder.class, WebClient::builder)
		.withPropertyValues("spring.cloud.gateway.server.webflux.metrics.provider=discovery");

	@Test
	void declaresThePolledEndpointsAsOpen() {
		this.runner.run((context) -> {
			SecuredPaths declared = context.getBean("discoveryMetricsSecuredPaths", SecuredPaths.class);
			// Open, not governed: the fan-out carries no credentials, so a console asking
			// for a principal would leave every instance reporting its own traffic alone.
			assertThat(declared.openPaths()).containsExactly("/ui/metrics/local", "/ui/metrics/local/instance");
			assertThat(declared.paths()).isEmpty();
			assertThat(declared.writePaths()).isEmpty();
		});
	}

	@Test
	void declaresThePathsAsTheyAreConfigured() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.metrics.discovery.path=/internal/metrics",
					"spring.cloud.gateway.server.webflux.metrics.discovery.instance-path=/internal/metrics/instance")
			.run((context) -> assertThat(
					context.getBean("discoveryMetricsSecuredPaths", SecuredPaths.class).openPaths())
				.containsExactly("/internal/metrics", "/internal/metrics/instance"));
	}

	@Test
	void declaresNothingWhenAnotherProviderIsSelected() {
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
					DiscoveryMetricsAutoConfiguration.class))
			.run((context) -> assertThat(context).doesNotHaveBean("discoveryMetricsSecuredPaths"));
	}

}
