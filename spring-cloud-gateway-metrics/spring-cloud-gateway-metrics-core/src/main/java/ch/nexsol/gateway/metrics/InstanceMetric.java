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

import java.util.Collections;
import java.util.List;

/**
 * The technical figures of a single gateway instance, whatever source they were read
 * from.
 * <p>
 * Unlike {@link RouteMetric} these are never merged: one instance is one row, so the
 * figures stay raw and the view divides. A saturation or a heap share computed here would
 * be a second copy of a number that can drift from the one it was computed out of.
 * <p>
 * Anything the running JVM does not expose is reported as {@code -1} rather than
 * {@code 0} or {@code null}: {@code jvm.memory.max} already answers {@code -1} when the
 * pool is unbounded, and the file descriptor figures do not exist outside Unix. A zero
 * would read as "no file open", which is a different and wrong statement.
 *
 * @param instanceId the identifier of the instance, as {@link InstanceIdentity} resolved
 * it
 * @param uri where the instance is reachable, {@code null} when the source does not know
 * it
 * @param uptimeSeconds how long the instance has been running, in seconds. A plain number
 * rather than a {@code Duration}: this record travels between instances as JSON, and a
 * duration renders either as decimal seconds or as an ISO-8601 string depending on which
 * Jackson modules the application registered
 * @param jvm the memory, garbage collection and thread figures
 * @param system the processor and file descriptor figures
 * @param netty the event loop figures of the Reactor Netty transport
 * @param pools the connection pools towards the downstream services
 * @param instrumentation which optional Reactor Netty instrumentation this instance runs
 * with
 */
public record InstanceMetric(String instanceId, String uri, long uptimeSeconds, JvmStats jvm, SystemStats system,
		NettyStats netty, List<PoolStats> pools, InstanceInstrumentation instrumentation) {

	public InstanceMetric {
		pools = Collections.unmodifiableList(List.copyOf(pools));
	}

	/**
	 * The memory, garbage collection and thread figures of one instance.
	 *
	 * @param heapUsedBytes the heap currently in use
	 * @param heapMaxBytes the heap ceiling, {@code -1} when the heap is unbounded
	 * @param nonHeapUsedBytes the non-heap memory currently in use
	 * @param gcOverhead the share of uptime spent collecting, between 0 and 1
	 * @param gcPauseTotalMs the total time spent in collection pauses
	 * @param gcPauseCount the number of collection pauses
	 * @param threadsLive the live thread count
	 * @param threadsPeak the highest thread count reached
	 * @param threadsDaemon the daemon thread count
	 */
	public record JvmStats(long heapUsedBytes, long heapMaxBytes, long nonHeapUsedBytes, double gcOverhead,
			double gcPauseTotalMs, long gcPauseCount, int threadsLive, int threadsPeak, int threadsDaemon) {

	}

	/**
	 * The processor and file descriptor figures of one instance.
	 *
	 * @param processCpuUsage the processor share used by this JVM, between 0 and 1
	 * @param systemCpuUsage the processor share used by the whole host, between 0 and 1
	 * @param loadAverage1m the one-minute load average, {@code -1} when unavailable
	 * @param cpuCount the number of processors visible to the JVM
	 * @param openFiles the open file descriptors, {@code -1} outside Unix
	 * @param maxFiles the file descriptor ceiling, {@code -1} outside Unix
	 */
	public record SystemStats(double processCpuUsage, double systemCpuUsage, double loadAverage1m, int cpuCount,
			long openFiles, long maxFiles) {

	}

	/**
	 * The event loop figures of the Reactor Netty transport.
	 * <p>
	 * Pending tasks are the WebFlux-specific signal that something blocks the loop: a
	 * gateway whose event loops queue work is one where every route slows down at once,
	 * for a reason no per-route figure shows.
	 *
	 * @param eventLoopPendingTasks the tasks queued across every event loop
	 * @param eventLoops the number of event loops reporting
	 */
	public record NettyStats(long eventLoopPendingTasks, int eventLoops) {

	}

	/**
	 * One connection pool of the gateway towards one downstream address.
	 * <p>
	 * A pool filling up towards a slow backend is the failure this whole view exists to
	 * make visible: every route pointing at that address collapses at once while the JVM
	 * itself looks perfectly healthy.
	 * <p>
	 * The counters are doubles because Reactor Netty publishes them as gauges; rounding
	 * them to longs would claim an exactness the reading does not have.
	 *
	 * @param name the name of the connection provider
	 * @param remoteAddress the downstream address the pool connects to
	 * @param active the connections currently in use
	 * @param idle the connections held open and unused
	 * @param pending the callers waiting for a connection
	 * @param max the connection ceiling of the pool
	 * @param maxPending the ceiling on waiting callers
	 * @param pendingTimeAvgMs the average wait before a connection is handed out
	 */
	public record PoolStats(String name, String remoteAddress, double active, double idle, double pending, double max,
			double maxPending, double pendingTimeAvgMs) {

	}

	/**
	 * Which optional Reactor Netty instrumentation an instance runs with.
	 * <p>
	 * This travels per instance rather than as one note on the snapshot, because nothing
	 * guarantees every instance was configured the same way &mdash; and one instance
	 * configured differently from its siblings is exactly what this view should reveal.
	 * <p>
	 * It exists because an empty {@link InstanceMetric#pools()} is otherwise ambiguous:
	 * either the counters are off, or no downstream has been called yet. Those two call
	 * for opposite actions, so the view must be able to tell them apart.
	 *
	 * @param connectionPool whether the connection pool counters are collected
	 * @param httpClient whether the HTTP client and event loop counters are collected
	 */
	public record InstanceInstrumentation(boolean connectionPool, boolean httpClient) {

	}

}
