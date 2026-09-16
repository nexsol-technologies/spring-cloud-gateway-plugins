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

package ch.nexsol.gateway.ui.autoconfigure;

import java.util.List;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.metrics.autoconfigure.MetricsAutoConfiguration;
import ch.nexsol.gateway.servicegraph.autoconfigure.ServiceGraphAutoConfiguration;
import ch.nexsol.gateway.ui.insights.ActuatorInstances;
import ch.nexsol.gateway.ui.insights.ActuatorInstances.Instance;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console on a gateway that publishes no figures.
 * <p>
 * {@link InstanceIdentity} is declared by the metrics plugin and by the service graph
 * plugin, and by nobody else. A gateway switching both off keeps the introspection views,
 * which name the instance they read: asked for as a bean rather than resolved, the
 * identity takes the whole context down at start-up, and the gateway does not start at
 * all.
 */
class GatewayUiWithoutTheFigurePluginsTests {

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(GatewayUiAutoConfiguration.class, MetricsAutoConfiguration.class,
				ServiceGraphAutoConfiguration.class));

	@Test
	void shouldStartWithTheMetricsAndServiceGraphPluginsSwitchedOff() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.metrics.enabled=false",
					"spring.cloud.gateway.server.webflux.service-graph.enabled=false")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(InstanceIdentity.class);
				assertThat(context).hasSingleBean(ActuatorInstances.class);
			});
	}

	@Test
	void shouldStillNameTheInstanceTheIntrospectionViewsRead() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.metrics.enabled=false",
					"spring.cloud.gateway.server.webflux.service-graph.enabled=false")
			.run((context) -> {
				List<Instance> instances = context.getBean(ActuatorInstances.class).list().block();
				assertThat(instances).hasSize(1);
				assertThat(instances.get(0).self()).isTrue();
				// Whatever the host is called. What matters is that the view has a name
				// to
				// put on the instance answering it rather than none at all.
				assertThat(instances.get(0).id()).isNotBlank();
			});
	}

	@Test
	void shouldTakeTheIdentityOfThePluginsWhenOneOfThemDeclaresIt() {
		// The fallback is a fallback: a gateway running the metrics plugin names its
		// instances through it, and the console must read the same name the traffic and
		// runtime views report.
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.metrics.instance-id=gateway-7")
			.run((context) -> {
				assertThat(context).hasSingleBean(InstanceIdentity.class);
				List<Instance> instances = context.getBean(ActuatorInstances.class).list().block();
				assertThat(instances).extracting(Instance::id).containsExactly("gateway-7");
			});
	}

}
