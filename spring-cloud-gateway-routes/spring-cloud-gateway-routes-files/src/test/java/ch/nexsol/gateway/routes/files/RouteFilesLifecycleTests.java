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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the initial load and the filesystem watching of the route files.
 */
class RouteFilesLifecycleTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final AtomicInteger refreshEvents = new AtomicInteger();

	private final ApplicationEventPublisher publisher = (event) -> {
		if (event instanceof RefreshRoutesEvent) {
			this.refreshEvents.incrementAndGet();
		}
	};

	private RouteFilesLifecycle lifecycle;

	@AfterEach
	void stopLifecycle() {
		if (this.lifecycle != null) {
			this.lifecycle.stop();
		}
	}

	private static void write(Path file, String routeId) throws IOException {
		Files.writeString(file, String.join("\n", "routes:", "  - id: " + routeId, "    uri: https://example.org",
				"    predicates:", "      - Path=/api/**", ""));
	}

	private FileRouteDefinitionLoader loader(String location) {
		return new FileRouteDefinitionLoader(new RouteDefinitionFileParser(), List.of(location),
				new PathMatchingResourcePatternResolver());
	}

	private FileRouteDefinitionLocator locator(FileRouteDefinitionLoader loader) {
		return new FileRouteDefinitionLocator(loader, this.publisher);
	}

	private List<String> routeIds(FileRouteDefinitionLocator locator) {
		return locator.getRouteDefinitions().map(RouteDefinition::getId).collectList().block();
	}

	@Test
	void loadsTheRoutesWhenItStarts(@TempDir Path directory) throws IOException {
		write(directory.resolve("routes.yaml"), "orders");
		FileRouteDefinitionLocator locator = locator(loader("file:" + directory + "/*.yaml"));
		this.lifecycle = new RouteFilesLifecycle(locator, loader("file:" + directory + "/*.yaml"), false);

		this.lifecycle.start();

		// The load is blocking on purpose: the routes must be there before startup
		// completes.
		assertThat(routeIds(locator)).containsExactly("orders");
		assertThat(this.lifecycle.isRunning()).isTrue();
	}

	@Test
	void reloadsWhenAWatchedFileChanges(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("routes.yaml");
		write(file, "orders");
		String location = "file:" + directory + "/*.yaml";
		FileRouteDefinitionLoader loader = loader(location);
		FileRouteDefinitionLocator locator = locator(loader);
		this.lifecycle = new RouteFilesLifecycle(locator, loader, true);
		this.lifecycle.start();
		assertThat(routeIds(locator)).containsExactly("orders");

		write(file, "invoices");

		assertThat(awaitRouteIds(locator, "invoices")).containsExactly("invoices");
	}

	@Test
	void publishesARefreshEventForTheReload(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("routes.yaml");
		write(file, "orders");
		String location = "file:" + directory + "/*.yaml";
		FileRouteDefinitionLoader loader = loader(location);
		FileRouteDefinitionLocator locator = locator(loader);
		this.lifecycle = new RouteFilesLifecycle(locator, loader, true);
		this.lifecycle.start();
		int afterStart = this.refreshEvents.get();

		write(file, "invoices");
		awaitRouteIds(locator, "invoices");

		// Without the event the gateway would keep serving the routes it built at
		// startup.
		assertThat(this.refreshEvents.get()).isGreaterThan(afterStart);
	}

	@Test
	void startsWithoutAWatcherWhenNoDirectoryCanBeWatched() {
		String location = "classpath:routes/sample-routes.yaml";
		FileRouteDefinitionLoader loader = loader(location);
		FileRouteDefinitionLocator locator = locator(loader);
		this.lifecycle = new RouteFilesLifecycle(locator, loader, true);

		this.lifecycle.start();

		assertThat(routeIds(locator)).containsExactly("after_route");
		assertThat(this.lifecycle.isRunning()).isTrue();
	}

	@Test
	void stopsTheWatcher(@TempDir Path directory) throws IOException {
		write(directory.resolve("routes.yaml"), "orders");
		String location = "file:" + directory + "/*.yaml";
		FileRouteDefinitionLoader loader = loader(location);
		this.lifecycle = new RouteFilesLifecycle(locator(loader), loader, true);
		this.lifecycle.start();

		this.lifecycle.stop();

		assertThat(this.lifecycle.isRunning()).isFalse();
	}

	@Test
	void stopsCleanlyWhenItNeverWatched(@TempDir Path directory) throws IOException {
		write(directory.resolve("routes.yaml"), "orders");
		String location = "file:" + directory + "/*.yaml";
		FileRouteDefinitionLoader loader = loader(location);
		this.lifecycle = new RouteFilesLifecycle(locator(loader), loader, false);
		this.lifecycle.start();

		this.lifecycle.stop();

		assertThat(this.lifecycle.isRunning()).isFalse();
	}

	@Test
	void runsInTheLastStartupPhase() {
		FileRouteDefinitionLoader loader = loader("classpath:routes/sample-routes.yaml");

		// The gateway route infrastructure must be up before the initial refresh event.
		assertThat(new RouteFilesLifecycle(locator(loader), loader, false).getPhase()).isEqualTo(Integer.MAX_VALUE);
	}

	private List<String> awaitRouteIds(FileRouteDefinitionLocator locator, String expected)
			throws InterruptedException {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		List<String> ids = routeIds(locator);
		while (!ids.contains(expected) && System.nanoTime() < deadline) {
			Thread.sleep(100);
			ids = routeIds(locator);
		}
		return ids;
	}

}
