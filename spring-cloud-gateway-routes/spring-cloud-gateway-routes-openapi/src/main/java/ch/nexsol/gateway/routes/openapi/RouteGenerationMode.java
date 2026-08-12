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

package ch.nexsol.gateway.routes.openapi;

/**
 * Strategy used to turn an OpenAPI document into gateway route definitions.
 */
public enum RouteGenerationMode {

	/**
	 * One route definition per OpenAPI operation, each matching a single path and HTTP
	 * method.
	 */
	PER_OPERATION,

	/**
	 * A single route definition per source, matching all the paths and HTTP methods of
	 * the document.
	 */
	AGGREGATED,

	/**
	 * No route at all: the source is declared for its contract alone.
	 * <p>
	 * This is for a service whose routes are declared elsewhere &mdash; by hand, by the
	 * discovery locator, in a route file, in the database &mdash; and which is listed
	 * here only so its contract joins the aggregated Swagger UI of
	 * {@code spring-cloud-gateway-hub-openapi}: that plugin builds documentation routes
	 * for the services it discovers on its own, and a service routed any other way is
	 * otherwise absent from it.
	 * <p>
	 * The document is not even read, since nothing is generated from it: fetching it is
	 * the hub's business, when the console asks for it. Only {@code id}, {@code spec-url}
	 * and {@code path-prefix} are read &mdash; the rest of the source configures routes
	 * that do not exist.
	 */
	NO_ROUTE

}
