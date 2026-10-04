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

package ch.nexsol.gateway.metrics.prometheus.autoconfigure;

import ch.nexsol.gateway.metrics.prometheus.PrometheusQueryResponse;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers, for a native image, the reflection the Prometheus answers are read through.
 * <p>
 * The query API answers JSON, decoded into {@link PrometheusQueryResponse} with
 * {@code bodyToMono}, and a record is bound reflectively like any other type. Nothing
 * else registers it: a record is only covered when it is a
 * {@code @ConfigurationProperties} class or a value the framework itself walks. The
 * nested data and samples come with it, since the registrar walks what a type holds.
 */
class PrometheusMetricsRuntimeHints implements RuntimeHintsRegistrar {

	private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		this.bindingRegistrar.registerReflectionHints(hints.reflection(), PrometheusQueryResponse.class);
	}

}
