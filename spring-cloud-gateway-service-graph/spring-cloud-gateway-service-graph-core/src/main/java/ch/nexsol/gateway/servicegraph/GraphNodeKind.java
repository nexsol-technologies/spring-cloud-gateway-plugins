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

package ch.nexsol.gateway.servicegraph;

/**
 * What a node of the graph stands for.
 * <p>
 * The kind is derived, never declared: a node the gateway routed to at least once is a
 * {@link #SERVICE}, and one that only ever called is a {@link #CALLER}. A gateway every
 * call transits &mdash; where a service reaches another one through the gateway rather
 * than directly &mdash; therefore sees its downstream services as services, on both sides
 * of the edges they take part in, and only the outermost clients as callers.
 */
public enum GraphNodeKind {

	/** An endpoint the gateway routed to, named after the target of the route. */
	SERVICE,

	/** An endpoint that only ever called, named by {@link CallerResolver}. */
	CALLER

}
