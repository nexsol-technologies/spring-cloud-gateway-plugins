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

package ch.nexsol.gateway.database.model;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.validation.annotation.Validated;

/**
 * Creation payload for a route, carrying its business id, target URI, order and the
 * predicates and filters to attach.
 *
 * @param routeId the business route id, must not be empty
 * @param uri the target URI, must not be {@code null}
 * @param order the resolution order, or {@code null} when unset
 * @param predicates the predicates to attach, must not be empty
 * @param filters the filters to attach
 */
@Validated
public record RouteCreateModel(@NotEmpty String routeId, @NotNull URI uri, Integer order,
		@NotEmpty List<@NotNull PredicateCreateModel> predicates, List<FilterCreateModel> filters) {

	/**
	 * Canonical constructor validating and normalizing the supplied URI.
	 * @param routeId the business route id
	 * @param uri the target URI
	 * @param order the resolution order
	 * @param predicates the predicates to attach
	 * @param filters the filters to attach
	 */
	public RouteCreateModel(@NotEmpty String routeId, @NotNull URI uri, Integer order,
			@NotEmpty List<@NotNull PredicateCreateModel> predicates, List<FilterCreateModel> filters) {
		this.routeId = routeId;
		try {
			// this.uri = new URL(uri.toASCIIString()).toURI();
			this.uri = new URI(uri.toASCIIString()).toURL().toURI();
		}
		catch (MalformedURLException | URISyntaxException ex) {
			throw new UnsupportedOperationException("URI not valid", ex);
		}
		this.order = order;
		this.predicates = predicates;
		this.filters = filters;
	}

}
