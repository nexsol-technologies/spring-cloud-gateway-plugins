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

package ch.nexsol.gateway.database.entity;

/**
 * Common contract for persisted route elements (predicates and filters) that are tied to
 * a route and carry a name plus a serialized argument payload.
 */
public interface RouteElementEntity {

	/**
	 * Returns the id of the route this element belongs to.
	 * @return the route reference id
	 */
	Long getRouteRefId();

	/**
	 * Sets the id of the route this element belongs to.
	 * @param routeRefId the route reference id
	 */
	void setRouteRefId(Long routeRefId);

	/**
	 * Returns the name of the gateway predicate or filter.
	 * @return the element name
	 */
	String getName();

	/**
	 * Sets the name of the gateway predicate or filter.
	 * @param name the element name
	 */
	void setName(String name);

	/**
	 * Returns the serialized (JSON) arguments of this element.
	 * @return the serialized arguments
	 */
	String getArgs();

	/**
	 * Sets the serialized (JSON) arguments of this element.
	 * @param args the serialized arguments
	 */
	void setArgs(String args);

}
