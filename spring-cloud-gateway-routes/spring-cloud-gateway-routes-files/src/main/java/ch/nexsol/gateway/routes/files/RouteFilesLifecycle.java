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

package ch.nexsol.gateway.routes.files;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.SmartLifecycle;

/**
 * Loads the file routes once the context is running and, when watching is enabled, keeps
 * a daemon thread that reloads them whenever a watched directory changes. Runs in the
 * last startup phase so the gateway route infrastructure is ready before the initial
 * refresh event is published.
 */
public class RouteFilesLifecycle implements SmartLifecycle {

	private static final Logger LOG = LoggerFactory.getLogger(RouteFilesLifecycle.class);

	/**
	 * How long the watcher waits for a change to settle before reloading. Long enough to
	 * fold the burst of events one save produces, short enough to stay unnoticed.
	 */
	private static final Duration QUIET_PERIOD = Duration.ofMillis(300);

	private final FileRouteDefinitionLocator locator;

	private final FileRouteDefinitionLoader loader;

	private final boolean watch;

	private volatile boolean running;

	private WatchService watchService;

	private Thread watchThread;

	/**
	 * Creates the lifecycle.
	 * @param locator the locator to refresh
	 * @param loader the loader providing the directories to watch
	 * @param watch whether to watch the filesystem for changes
	 */
	public RouteFilesLifecycle(FileRouteDefinitionLocator locator, FileRouteDefinitionLoader loader, boolean watch) {
		this.locator = locator;
		this.loader = loader;
		this.watch = watch;
	}

	@Override
	public void start() {
		this.running = true;
		// Block on the initial load so routes are available before startup completes.
		this.locator.refresh().block();
		if (this.watch) {
			startWatching();
		}
	}

	private void startWatching() {
		Set<Path> directories = this.loader.watchableDirectories();
		if (directories.isEmpty()) {
			LOG.info("No filesystem directories to watch for route files");
			return;
		}
		try {
			this.watchService = FileSystems.getDefault().newWatchService();
			for (Path directory : directories) {
				directory.register(this.watchService, StandardWatchEventKinds.ENTRY_CREATE,
						StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
			}
		}
		catch (IOException ex) {
			throw new RouteDefinitionFileException("Unable to watch route file directories", ex);
		}
		this.watchThread = new Thread(this::watchLoop, "routes-files-watch");
		this.watchThread.setDaemon(true);
		this.watchThread.start();
		LOG.info("Watching {} directory(ies) for route file changes", directories.size());
	}

	private void watchLoop() {
		while (this.running) {
			WatchKey key;
			try {
				key = this.watchService.take();
			}
			catch (ClosedWatchServiceException | InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			boolean changed = !key.pollEvents().isEmpty();
			key.reset();
			if (!changed) {
				continue;
			}
			if (!drainPendingEvents()) {
				return;
			}
			LOG.debug("Route file change detected, refreshing route definitions");
			this.locator.refresh().subscribe();
		}
	}

	/**
	 * Waits for the burst of events a single change produces to settle.
	 * <p>
	 * Saving one file emits several events &mdash; an editor writing through a temporary
	 * file emits a create, a modify and a delete &mdash; and each of them would otherwise
	 * reload and re-parse every configured file. Consuming what is already pending folds
	 * them into one reload.
	 * @return {@code false} when the wait was interrupted and the loop must stop
	 */
	private boolean drainPendingEvents() {
		try {
			WatchKey pending = this.watchService.poll(QUIET_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
			while (pending != null) {
				pending.pollEvents();
				pending.reset();
				pending = this.watchService.poll(QUIET_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
			}
			return true;
		}
		catch (ClosedWatchServiceException | InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	@Override
	public void stop() {
		this.running = false;
		if (this.watchService != null) {
			try {
				this.watchService.close();
			}
			catch (IOException ex) {
				LOG.debug("Error closing route file watch service", ex);
			}
		}
		if (this.watchThread != null) {
			this.watchThread.interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	@Override
	public int getPhase() {
		return Integer.MAX_VALUE;
	}

}
