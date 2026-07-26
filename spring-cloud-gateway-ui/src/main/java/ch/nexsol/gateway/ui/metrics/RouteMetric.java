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

package ch.nexsol.gateway.ui.metrics;

/**
 * Aggregated request metrics for a single gateway route, used as a data point in the
 * traffic bubble chart.
 *
 * @param routeId the route identifier
 * @param uri the route target URI, or {@code null} when unknown
 * @param count the total number of requests routed
 * @param avgMs the mean response time in milliseconds
 * @param maxMs the maximum response time in milliseconds
 * @param errorCount the number of server-error (5xx) responses
 * @param errorRate the fraction of server-error responses, between 0 and 1
 */
public record RouteMetric(String routeId, String uri, long count, double avgMs, double maxMs, long errorCount,
		double errorRate) {
}
