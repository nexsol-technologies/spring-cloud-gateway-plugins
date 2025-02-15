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

@Validated
public record RouteCreateModel(@NotEmpty String routeId, @NotNull URI uri, Integer order,
		@NotEmpty List<@NotNull PredicateCreateModel> predicates, List<FilterCreateModel> filters) {

	public RouteCreateModel(@NotEmpty String routeId, @NotNull URI uri, Integer order,
			@NotEmpty List<@NotNull PredicateCreateModel> predicates, List<FilterCreateModel> filters) {
		this.routeId = routeId;
		try {
			// this.uri = new URL(uri.toASCIIString()).toURI();
			this.uri = new URI(uri.toASCIIString()).toURL().toURI();
		}
		catch (MalformedURLException | URISyntaxException e) {
			throw new UnsupportedOperationException("URI not valid", e);
		}
		this.order = order;
		this.predicates = predicates;
		this.filters = filters;
	}

}
