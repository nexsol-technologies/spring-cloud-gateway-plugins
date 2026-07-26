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

/**
 * Result of evaluating one predicate of a route against the tested request. The
 * predicates of a route are reported individually, which is what shows why a route did
 * not match.
 *
 * @param description the predicate as the gateway describes it, e.g.
 * {@code Paths: [/api/**], match trailing slash: true}
 * @param matched whether this predicate accepted the request
 * @param error the failure message when the predicate could not be evaluated, otherwise
 * {@code null}
 */
public record PredicateOutcome(String description, boolean matched, String error) {
}
