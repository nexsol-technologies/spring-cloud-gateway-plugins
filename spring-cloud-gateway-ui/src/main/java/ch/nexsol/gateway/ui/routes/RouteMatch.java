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

package ch.nexsol.gateway.ui.routes;

import java.util.List;

/**
 * Outcome of testing a single route against a request: the verdict of the route as a
 * whole, plus the verdict of each of its predicates.
 *
 * @param routeId the route identifier
 * @param uri the route target URI
 * @param order the route order; lower values are matched first
 * @param source the name of the locator the route definition came from, or {@code null}
 * when the route was not built from a definition
 * @param matched whether the gateway would hand the request to this route
 * @param error the failure message when the route predicate could not be evaluated,
 * otherwise {@code null}
 * @param predicates the per-predicate outcomes explaining the verdict
 * @param filters the filters the route would apply, in the shortcut form
 */
public record RouteMatch(String routeId, String uri, int order, String source, boolean matched, String error,
		List<PredicateOutcome> predicates, List<String> filters) {
}
