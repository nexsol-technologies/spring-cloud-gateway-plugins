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
 * Result of a route test: the request as it was evaluated, the route the gateway would
 * pick and the verdict of every candidate route in matching order.
 *
 * @param method the HTTP method of the tested request
 * @param uri the absolute URI of the tested request
 * @param matchedRouteId the id of the first matching route, or {@code null} when the
 * request would not be routed
 * @param routes the verdict of every route, in the order the gateway evaluates them
 * @param error the failure message when the request itself could not be built, otherwise
 * {@code null}
 */
public record RouteTestReport(String method, String uri, String matchedRouteId, List<RouteMatch> routes, String error) {
}
