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

package ch.nexsol.gateway.audit;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Bounds the audit events a publisher holds while its backend is slower than the traffic.
 * <p>
 * A publisher hands its events over without waiting for them, so nothing slows the
 * requests down when the backend does: the events pile up instead &mdash; in the command
 * queue of the Redis client, behind a connection pool that never times out, in the queue
 * of a scheduler &mdash; each holding its payload, until the heap is gone. Past the limit
 * an event is dropped, counted and reported, which is the lesser evil for a gateway that
 * must keep routing.
 * <p>
 * What is dropped or failed is logged at most once per {@link #REPORT_INTERVAL}, with the
 * totals: an outage would otherwise write one line, and one stack trace, per request.
 */
public final class AuditBacklog {

	/** How long the drops and the failures stay out of the log once reported. */
	public static final Duration REPORT_INTERVAL = Duration.ofSeconds(30);

	private static final Logger LOG = LoggerFactory.getLogger(AuditBacklog.class);

	private final String backend;

	private final int limit;

	private final AtomicInteger pending = new AtomicInteger();

	private final AtomicLong dropped = new AtomicLong();

	private final AtomicLong failed = new AtomicLong();

	private final AtomicLong reportedAt = new AtomicLong(System.nanoTime() - REPORT_INTERVAL.toNanos());

	/**
	 * Create a new backlog.
	 * @param backend what the events are sent to, as named in the log
	 * @param limit how many events may be pending at once; zero or less sets no limit
	 */
	public AuditBacklog(String backend, int limit) {
		this.backend = backend;
		this.limit = limit;
	}

	/**
	 * Takes a place for an event about to be sent, to be given back with
	 * {@link #release()} once it has been.
	 * @return {@code false} when too many events are pending already, in which case the
	 * event is counted as dropped and must not be sent
	 */
	public boolean tryAcquire() {
		if (this.pending.incrementAndGet() > this.limit && this.limit > 0) {
			this.pending.decrementAndGet();
			this.dropped.incrementAndGet();
			report(null);
			return false;
		}
		return true;
	}

	/**
	 * Gives back the place taken by {@link #tryAcquire()}.
	 */
	public void release() {
		this.pending.decrementAndGet();
	}

	/**
	 * Records an event the backend did not take.
	 * @param failure why it did not
	 */
	public void failed(Throwable failure) {
		this.failed.incrementAndGet();
		report(failure);
	}

	/**
	 * Sends an event without waiting for it, unless too many are pending already. The
	 * place it takes is given back when the send completes, fails, or outlasts the
	 * timeout &mdash; without which a backend that stops answering would keep every place
	 * for good, and every later event would be dropped.
	 * @param send the send to subscribe to, built only when the event is accepted
	 * @param timeout how long a send may take; zero or less sets no timeout
	 */
	public void submit(Supplier<? extends Mono<?>> send, Duration timeout) {
		if (!tryAcquire()) {
			return;
		}
		Mono<?> sending;
		try {
			sending = send.get();
		}
		catch (RuntimeException ex) {
			release();
			failed(ex);
			return;
		}
		if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
			sending = sending.timeout(timeout);
		}
		sending.subscribe(null, (ex) -> {
			release();
			failed(ex);
		}, this::release);
	}

	/**
	 * @return how many events are pending
	 */
	public int pending() {
		return this.pending.get();
	}

	/**
	 * @return how many events were dropped for want of room since the gateway started
	 */
	public long dropped() {
		return this.dropped.get();
	}

	/**
	 * @return how many events the backend did not take since the gateway started
	 */
	public long failed() {
		return this.failed.get();
	}

	private void report(Throwable failure) {
		long now = System.nanoTime();
		long last = this.reportedAt.get();
		if (now - last < REPORT_INTERVAL.toNanos() || !this.reportedAt.compareAndSet(last, now)) {
			if (failure != null) {
				LOG.debug("failed to publish an audit event to {}", this.backend, failure);
			}
			return;
		}
		LOG.warn(
				"audit events are being lost towards {}: {} dropped over the limit of {} pending, {} failed{} "
						+ "(totals since startup, reported at most every {} s)",
				this.backend, this.dropped.get(), this.limit, this.failed.get(),
				(failure != null) ? ", last failure: " + failure : "", REPORT_INTERVAL.toSeconds());
	}

}
