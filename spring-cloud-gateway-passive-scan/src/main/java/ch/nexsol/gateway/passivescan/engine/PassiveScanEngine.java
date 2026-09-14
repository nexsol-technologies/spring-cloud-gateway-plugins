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

package ch.nexsol.gateway.passivescan.engine;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import ch.nexsol.gateway.passivescan.model.Finding;
import ch.nexsol.gateway.passivescan.model.HttpExchange;
import ch.nexsol.gateway.passivescan.scanner.PassiveScanner;
import ch.nexsol.gateway.passivescan.store.FindingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;

/**
 * Runs the enabled {@link PassiveScanner}s over the exchanges the web filter submits, off
 * the request hot path.
 * <p>
 * The web filter runs concurrently on every Netty event-loop thread, so exchanges are
 * handed over through a bounded {@link BlockingQueue} &mdash; whose {@code offer} is
 * thread-safe and never blocks &mdash; and drained by a single worker thread. Once
 * {@code queueCapacity} exchanges are awaiting inspection, further submissions are
 * dropped and counted rather than growing memory without bound. A Reactor {@code Sinks}
 * is not used here on purpose: it rejects the concurrent emission this filter produces.
 */
public class PassiveScanEngine implements DisposableBean {

	private static final Logger LOG = LoggerFactory.getLogger(PassiveScanEngine.class);

	private static final long POLL_TIMEOUT_MS = 500;

	private final List<PassiveScanner> scanners;

	private final FindingStore store;

	private final List<FindingListener> listeners;

	private final BlockingQueue<HttpExchange> queue;

	private final ExecutorService worker;

	private final AtomicLong dropped = new AtomicLong();

	private volatile boolean running = true;

	public PassiveScanEngine(List<PassiveScanner> scanners, FindingStore store, int queueCapacity) {
		this(scanners, store, List.of(), queueCapacity);
	}

	public PassiveScanEngine(List<PassiveScanner> scanners, FindingStore store, List<FindingListener> listeners,
			int queueCapacity) {
		this.scanners = List.copyOf(scanners);
		this.store = store;
		this.listeners = List.copyOf(listeners);
		this.queue = new ArrayBlockingQueue<>(Math.max(queueCapacity, 1));
		this.worker = Executors.newSingleThreadExecutor((runnable) -> {
			Thread thread = new Thread(runnable, "passive-scan");
			thread.setDaemon(true);
			return thread;
		});
		this.worker.execute(this::drain);
	}

	/**
	 * Submit a completed exchange for inspection. Non-blocking; drops the exchange when
	 * the inspection queue is full.
	 * @param exchange the completed exchange
	 */
	public void submit(HttpExchange exchange) {
		if (!this.queue.offer(exchange)) {
			this.dropped.incrementAndGet();
		}
	}

	/**
	 * Run every scanner over the exchange synchronously, isolating each scanner's
	 * failures so one broken scanner does not stop the others.
	 * @param exchange the exchange to inspect
	 */
	public void scan(HttpExchange exchange) {
		for (PassiveScanner scanner : this.scanners) {
			try {
				List<Finding> findings = scanner.inspect(exchange);
				for (Finding finding : findings) {
					this.store.record(finding);
					notifyListeners(finding);
				}
			}
			catch (RuntimeException ex) {
				LOG.warn("passive scanner {} failed", scanner.id(), ex);
			}
		}
	}

	private void drain() {
		while (this.running) {
			try {
				HttpExchange exchange = this.queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				if (exchange != null) {
					scan(exchange);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (RuntimeException ex) {
				// The drain loop must outlive any single failure: keep going.
				LOG.warn("passive scan worker iteration failed", ex);
			}
		}
	}

	private void notifyListeners(Finding finding) {
		for (FindingListener listener : this.listeners) {
			try {
				listener.onFinding(finding);
			}
			catch (RuntimeException ex) {
				LOG.warn("passive scan finding listener failed", ex);
			}
		}
	}

	/**
	 * The number of exchanges dropped because the inspection queue was full.
	 * @return the dropped count
	 */
	public long dropped() {
		return this.dropped.get();
	}

	@Override
	public void destroy() {
		this.running = false;
		this.worker.shutdownNow();
	}

}
