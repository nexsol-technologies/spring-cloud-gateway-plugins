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

package ch.nexsol.gateway.metrics.autoconfigure;

import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.RouteMetric;
import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers, for a native image, the reflection the readings are serialized through.
 * <p>
 * The figures leave the instance as JSON three ways &mdash; published to Redis, polled
 * from a sibling over the discovery client, rendered by the console &mdash; and a record
 * is bound reflectively like any other type. Nothing else registers them: a record is
 * only covered when it is a {@code @ConfigurationProperties} class or a value the
 * framework itself walks.
 * <p>
 * The nested figures of {@link InstanceMetric} come with it, since the registrar walks
 * what a type holds. The two readings are named alongside their snapshot because they are
 * also published on their own: a provider writes a list of readings, not a snapshot.
 */
class GatewayMetricsRuntimeHints implements RuntimeHintsRegistrar {

	private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		this.bindingRegistrar.registerReflectionHints(hints.reflection(), RouteMetricsSnapshot.class,
				InstanceMetricsSnapshot.class, RouteMetric.class, InstanceMetric.class);
	}

}
