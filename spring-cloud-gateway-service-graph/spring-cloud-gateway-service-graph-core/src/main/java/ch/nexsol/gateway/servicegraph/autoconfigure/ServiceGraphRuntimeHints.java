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

import ch.nexsol.gateway.servicegraph.GraphEdge;
import ch.nexsol.gateway.servicegraph.GraphNode;
import ch.nexsol.gateway.servicegraph.ServiceGraphSnapshot;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers, for a native image, the reflection the graph is serialized through.
 * <p>
 * The edges leave the instance as JSON &mdash; published to Redis, read back by whichever
 * instance answers the console &mdash; and a record is bound reflectively like any other
 * type. Nothing else registers them: a record is only covered when it is a
 * {@code @ConfigurationProperties} class or a value the framework itself walks.
 * <p>
 * The nodes and the edges come with the snapshot, since the registrar walks what a type
 * holds. They are named alongside it because they are also published on their own: a
 * provider writes a list of edges, not a snapshot.
 */
class ServiceGraphRuntimeHints implements RuntimeHintsRegistrar {

	private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		this.bindingRegistrar.registerReflectionHints(hints.reflection(), ServiceGraphSnapshot.class, GraphNode.class,
				GraphEdge.class);
	}

}
