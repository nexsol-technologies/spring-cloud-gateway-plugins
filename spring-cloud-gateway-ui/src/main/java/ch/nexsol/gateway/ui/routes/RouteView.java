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
 * A single route definition as resolved by the gateway, together with the source it was
 * read from.
 *
 * @param routeId the route identifier
 * @param uri the route target URI, or {@code null} when the definition carries none
 * @param order the route order; lower values are matched first
 * @param predicates the predicates rendered in the shortcut form, e.g.
 * {@code Path=/api/**}
 * @param filters the filters rendered in the shortcut form, e.g. {@code StripPrefix=1}
 * @param source the human-readable name of the locator the definition came from
 * @param duplicated whether another source declares a route with the same id
 */
public record RouteView(String routeId, String uri, int order, List<String> predicates, List<String> filters,
		String source, boolean duplicated) {
}
