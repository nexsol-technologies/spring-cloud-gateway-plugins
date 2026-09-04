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

package ch.nexsol.gateway.oauth2.autoconfigure;

import ch.nexsol.gateway.oauth2.filter.factory.AuthorizationTokenGatewayFilterFactory;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers, for a native image, the reflection this module's filter factory is read
 * through.
 * <p>
 * Spring Cloud Gateway contributes the same hints for its own factories from
 * {@code ConfigurableHintsRegistrationProcessor}, but that processor scans the
 * {@code org.springframework.cloud.gateway} package only: a factory declared anywhere
 * else is invisible to it and its {@code Config} has to be registered here, or the
 * shortcut arguments of every route using it fail to bind once the image is built.
 */
class GatewayOauth2RuntimeHints implements RuntimeHintsRegistrar {

	private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		Class<?> configClass = AuthorizationTokenGatewayFilterFactory.Config.class;
		hints.reflection()
			.registerType(configClass, MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS,
					MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
		// Pulls in what the config holds: the granted accesses and the match mode, which
		// the binder resolves through Enum.valueOf and therefore reflectively.
		this.bindingRegistrar.registerReflectionHints(hints.reflection(), configClass);
	}

}
