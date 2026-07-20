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
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * R2DBC entity mapping a route row to its business route id, target URI and order.
 */
@Table("route")
public class RouteEntity {

	@Id
	private Long id;

	@Column("route_id")
	private String routeId;

	private String uri;

	@Column("route_order")
	private Integer order;

	/**
	 * Creates an empty route entity.
	 */
	public RouteEntity() {

	}

	/**
	 * Returns the primary key of this route.
	 * @return the route id
	 */
	public Long getId() {
		return this.id;
	}

	/**
	 * Sets the primary key of this route.
	 * @param id the route id
	 */
	public void setId(Long id) {
		this.id = id;
	}

	/**
	 * Returns the business route identifier.
	 * @return the business route id
	 */
	public String getRouteId() {
		return this.routeId;
	}

	/**
	 * Sets the business route identifier.
	 * @param routeId the business route id
	 */
	public void setRouteId(String routeId) {
		this.routeId = routeId;
	}

	/**
	 * Returns the target URI of this route.
	 * @return the target URI
	 */
	public String getUri() {
		return this.uri;
	}

	/**
	 * Sets the target URI of this route.
	 * @param uri the target URI
	 */
	public void setUri(String uri) {
		this.uri = uri;
	}

	/**
	 * Returns the resolution order of this route.
	 * @return the route order, or {@code null} when unset
	 */
	public Integer getOrder() {
		return this.order;
	}

	/**
	 * Sets the resolution order of this route.
	 * @param order the route order
	 */
	public void setOrder(Integer order) {
		this.order = order;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String toString() {
		return "RouteEntity [id=" + this.id + ", routeId=" + this.routeId + ", uri=" + this.uri + ", order="
				+ this.order + "]";
	}

}
