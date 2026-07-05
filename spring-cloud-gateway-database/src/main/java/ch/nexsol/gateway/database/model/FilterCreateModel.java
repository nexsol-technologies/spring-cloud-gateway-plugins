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

import java.util.Map;

import jakarta.validation.constraints.NotEmpty;

import org.springframework.validation.annotation.Validated;

/**
 * Creation payload for a route filter, carrying the filter name and its arguments.
 *
 * @param name the filter name, must not be empty
 * @param args the filter arguments keyed by argument name
 */
@Validated
public record FilterCreateModel(@NotEmpty String name, Map<String, String> args) implements RouteElementCreateModel {

}
