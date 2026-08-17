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

package ch.nexsol.gateway.servicegraph.autoconfigure;

import ch.nexsol.gateway.servicegraph.CallerResolver;
import ch.nexsol.gateway.servicegraph.LocalServiceGraphSource;
import ch.nexsol.gateway.servicegraph.ServiceGraphFilter;
import ch.nexsol.gateway.servicegraph.ServiceGraphProperties;
import ch.nexsol.gateway.servicegraph.ServiceGraphSnapshot;
import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ServiceGraphAutoConfiguration}.
 */
class ServiceGraphAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, ServiceGraphAutoConfiguration.class));

	@Test
	void registersTheFilterAndTheLocalSource() {
		this.runner.run((context) -> {
			assertThat(context).hasSingleBean(ServiceGraphProperties.class);
			assertThat(context).hasSingleBean(CallerResolver.class);
			assertThat(context).hasSingleBean(ServiceGraphFilter.class);
			assertThat(context).hasSingleBean(LocalServiceGraphSource.class);
		});
	}

	@Test
	void registersNothingWhenThePluginIsDisabled() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.service-graph.enabled=false")
			.run((context) -> {
				assertThat(context).doesNotHaveBean(ServiceGraphFilter.class);
				assertThat(context).doesNotHaveBean(ServiceGraphSource.class);
			});
	}

	@Test
	void leavesTheLocalSourceOutWhenAProviderContributedOne() {
		this.runner.withUserConfiguration(ProviderConfiguration.class).run((context) -> {
			assertThat(context).doesNotHaveBean(LocalServiceGraphSource.class);
			assertThat(context).hasSingleBean(ServiceGraphSource.class);
		});
	}

	@Test
	void bindsTheCallerConfiguration() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.service-graph.caller.claims=aud",
					"spring.cloud.gateway.server.webflux.service-graph.caller.max=5")
			.run((context) -> {
				ServiceGraphProperties properties = context.getBean(ServiceGraphProperties.class);
				assertThat(properties.getCaller().getClaims()).containsExactly("aud");
				assertThat(properties.getCaller().getMax()).isEqualTo(5);
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class ProviderConfiguration {

		@Bean
		ServiceGraphSource providerServiceGraphSource() {
			return () -> Mono.just(ServiceGraphSnapshot.empty("from a provider"));
		}

	}

}
