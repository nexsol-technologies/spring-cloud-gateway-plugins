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

package ch.nexsol.gateway.metrics;

/**
 * The request figures of a single gateway route, whatever source they were read from.
 *
 * @param routeId the id of the route
 * @param uri the target the route resolves to, {@code null} when the source does not know
 * it
 * @param count the number of requests
 * @param avgMs the average latency in milliseconds
 * @param maxMs the slowest request in milliseconds
 * @param clientErrorCount the number of 4xx responses
 * @param clientErrorRate the share of 4xx responses, between 0 and 1
 * @param errorCount the number of 5xx responses
 * @param errorRate the share of 5xx responses, between 0 and 1
 */
public record RouteMetric(String routeId, String uri, long count, double avgMs, double maxMs, long clientErrorCount,
		double clientErrorRate, long errorCount, double errorRate) {

}
