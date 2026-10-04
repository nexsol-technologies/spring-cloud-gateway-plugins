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

package ch.nexsol.gateway.database.autoconfigure;

import ch.nexsol.gateway.database.model.FilterCreateModel;
import ch.nexsol.gateway.database.model.FilterResponseModel;
import ch.nexsol.gateway.database.model.PredicateCreateModel;
import ch.nexsol.gateway.database.model.PredicateResponseModel;
import ch.nexsol.gateway.database.model.RouteCreateModel;
import ch.nexsol.gateway.database.model.RouteResponseModel;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers, for a native image, the reflection the route management payloads are read
 * through.
 * <p>
 * The controllers of this module bind a route, a predicate and a filter from the request
 * body and answer them back, and the console renders the same records in its database
 * routes view: a record is bound reflectively like any other type, and nothing else
 * registers them. The entities are a separate matter &mdash; Spring Data contributes the
 * hints its repositories and their domain types need.
 */
class GatewayDatabaseRuntimeHints implements RuntimeHintsRegistrar {

	private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		this.bindingRegistrar.registerReflectionHints(hints.reflection(), RouteCreateModel.class,
				RouteResponseModel.class, PredicateCreateModel.class, PredicateResponseModel.class,
				FilterCreateModel.class, FilterResponseModel.class);
	}

}
