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

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * R2DBC entity mapping a route predicate row to its name, arguments and owning route.
 */
@Table("predicate")
public class PredicateEntity implements RouteElementEntity {

	@Id
	private Long id;

	private Long routeRefId;

	private String name;

	private String args;

	/**
	 * Creates an empty predicate entity.
	 */
	public PredicateEntity() {

	}

	/**
	 * Returns the primary key of this predicate.
	 * @return the predicate id
	 */
	public Long getId() {
		return this.id;
	}

	/**
	 * Sets the primary key of this predicate.
	 * @param id the predicate id
	 */
	public void setId(Long id) {
		this.id = id;
	}

	/**
	 * {@return the id of the route this predicate belongs to}
	 */
	@Override
	public Long getRouteRefId() {
		return this.routeRefId;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setRouteRefId(Long routeRefId) {
		this.routeRefId = routeRefId;
	}

	/**
	 * {@return the predicate name}
	 */
	@Override
	public String getName() {
		return this.name;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * {@return the serialized predicate arguments}
	 */
	@Override
	public String getArgs() {
		return this.args;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setArgs(String args) {
		this.args = args;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String toString() {
		return "PredicateEntity [id=" + this.id + ", routeRefId=" + this.routeRefId + ", name=" + this.name + ", args="
				+ this.args + "]";
	}

}
