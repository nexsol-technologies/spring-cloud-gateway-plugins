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

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a {@link ServiceGraphSource} returns: the graph together with the coverage it was
 * computed over.
 * <p>
 * The coverage is carried with the graph rather than inferred by the view, because it is
 * the only thing telling a reader what the picture leaves out. A graph built from the
 * counters of one instance is a sample of the traffic, and a sample that does not say so
 * reads as the whole.
 *
 * @param coverage what the graph covers, ready to be shown to a human &mdash; the local
 * instance id, the number of instances reached, the backend it was read from
 * @param nodes the endpoints the edges refer to, ordered by call count descending
 * @param edges the calls between them, ordered by call count descending
 */
public record ServiceGraphSnapshot(String coverage, List<GraphNode> nodes, List<GraphEdge> edges) {

	public ServiceGraphSnapshot {
		nodes = Collections.unmodifiableList(List.copyOf(nodes));
		edges = Collections.unmodifiableList(List.copyOf(edges));
	}

	/**
	 * An empty snapshot, used by a source that has nothing to report.
	 * @param coverage what the graph would have covered
	 * @return the empty snapshot
	 */
	public static ServiceGraphSnapshot empty(String coverage) {
		return new ServiceGraphSnapshot(coverage, List.of(), List.of());
	}

	/**
	 * Build a snapshot from partial edges, summing everything that joins the same two
	 * endpoints and deriving the nodes from what is left.
	 * <p>
	 * Every source needs this: the local one because the calls are counted per outcome,
	 * the consolidating ones because each instance reports the share it served.
	 * @param coverage what the graph covers
	 * @param edges the partial edges, in any order
	 * @return the merged graph
	 */
	public static ServiceGraphSnapshot of(String coverage, Collection<GraphEdge> edges) {
		Map<Endpoints, GraphEdge> merged = new LinkedHashMap<>();
		for (GraphEdge edge : edges) {
			merged.merge(new Endpoints(edge.from(), edge.to(), edge.routeId()), edge, ServiceGraphSnapshot::sum);
		}
		List<GraphEdge> mergedEdges = merged.values()
			.stream()
			.sorted(Comparator.comparingLong(GraphEdge::calls).reversed())
			.toList();
		return new ServiceGraphSnapshot(coverage, nodes(mergedEdges), mergedEdges);
	}

	private static GraphEdge sum(GraphEdge left, GraphEdge right) {
		return new GraphEdge(left.from(), left.to(), left.routeId(), left.calls() + right.calls(),
				left.errors() + right.errors());
	}

	/**
	 * Derives one node per distinct endpoint, whichever side of an edge it appeared on. A
	 * node the gateway routed to is a service, even when it also calls: in a topology
	 * where a service reaches another one through the gateway, the same endpoint is both,
	 * and showing it twice would split its traffic in half.
	 */
	private static List<GraphNode> nodes(List<GraphEdge> edges) {
		Set<String> services = new LinkedHashSet<>();
		Map<String, Long> totals = new LinkedHashMap<>();
		for (GraphEdge edge : edges) {
			services.add(edge.to());
			totals.merge(edge.from(), edge.calls(), Long::sum);
			totals.merge(edge.to(), edge.calls(), Long::sum);
		}
		return totals.entrySet()
			.stream()
			.map((entry) -> new GraphNode(entry.getKey(),
					services.contains(entry.getKey()) ? GraphNodeKind.SERVICE : GraphNodeKind.CALLER, entry.getValue()))
			.sorted(Comparator.comparingLong(GraphNode::calls).reversed())
			.toList();
	}

	/**
	 * What makes an edge distinct: the two endpoints and the route the calls went
	 * through.
	 */
	private record Endpoints(String from, String to, String routeId) {

	}

}
