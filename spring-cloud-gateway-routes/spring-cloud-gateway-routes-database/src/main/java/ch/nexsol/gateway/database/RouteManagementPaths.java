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

package ch.nexsol.gateway.database;

import java.util.List;

/**
 * The paths the route management serves.
 * <p>
 * This is the authoritative list, and the single source everything that reasons about
 * those paths reads: the declaration made to the console, the security chain the plugin
 * contributes, and the filter enforcing a read-only access. A path cannot be closed by
 * one and forgotten by another.
 * <p>
 * They are named one by one rather than as {@code /api/gateway/routes/**}: a gateway
 * route published under the same prefix is not this plugin, and must not inherit its
 * rules.
 * <p>
 * Only the API is here. The page over these routes is a view of the console, served and
 * declared by it, as every other view of the console is.
 */
public final class RouteManagementPaths {

	/**
	 * The paths of the REST API, which a client calls with a token and a JSON body.
	 */
	public static final List<String> API = List.of("/api/gateway/routes", "/api/gateway/routes/{id}",
			"/api/gateway/routes/available-predicates", "/api/gateway/routes/available-predicates/{predicate}/args",
			"/api/gateway/routes/available-filters", "/api/gateway/routes/available-filters/{filter}/args");

	private RouteManagementPaths() {
	}

}
