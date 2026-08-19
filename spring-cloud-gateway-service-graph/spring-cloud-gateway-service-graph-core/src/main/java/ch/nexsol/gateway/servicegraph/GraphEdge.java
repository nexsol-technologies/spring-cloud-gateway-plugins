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

package ch.nexsol.gateway.servicegraph;

/**
 * One directed edge of the graph: what one endpoint called, through which route, how
 * often, and how often it failed.
 * <p>
 * The route is part of what makes an edge, not a label on it: two routes leading to the
 * same target answer different questions of the same pair, and merging them would hide
 * which one carries the traffic. Two endpoints joined by two routes are therefore two
 * edges.
 * <p>
 * The failure share is left to the view rather than carried here, because a rate cannot
 * be merged &mdash; two partial edges are summed, and dividing before summing is how an
 * average of averages goes wrong.
 *
 * @param from the id of the calling node
 * @param to the id of the called node
 * @param routeId the id of the route the call went through, {@code null} when the source
 * does not know it &mdash; a graph read from a tracing backend describes calls that never
 * went through the gateway at all
 * @param calls the number of calls
 * @param errors the number of calls answered with a 5xx
 */
public record GraphEdge(String from, String to, String routeId, long calls, long errors) {

}
