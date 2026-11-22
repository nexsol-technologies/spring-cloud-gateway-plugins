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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests what the watcher makes of the files that are not route files. It watches whole
 * directories, since that is all a file system reports on: a log or a lock file written
 * next to the routes must neither reload them nor keep a real change from being applied.
 */
class RouteFilesLifecycleUnrelatedFilesTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final AtomicInteger loads = new AtomicInteger();

	private final AtomicBoolean writing = new AtomicBoolean(true);

	private RouteFilesLifecycle lifecycle;

	private FileRouteDefinitionLocator locator;

	@AfterEach
	void stop() {
		this.writing.set(false);
		if (this.lifecycle != null) {
			this.lifecycle.stop();
		}
	}

	@Test
	void aFileThatIsNotARouteFileReloadsNothing(@TempDir Path directory) throws Exception {
		write(directory.resolve("routes.yaml"), "orders");
		start(directory);
		int afterStart = this.loads.get();

		for (int i = 0; i < 5; i++) {
			Files.writeString(directory.resolve("notes.txt"), "note " + i);
			Thread.sleep(100);
		}
		Thread.sleep(1000);

		assertThat(this.loads).hasValue(afterStart);
	}

	@Test
	void onlyTheRouteFilesConcernTheWatcher(@TempDir Path directory) throws Exception {
		Path routes = directory.resolve("routes.yaml");
		write(routes, "orders");
		start(directory);

		assertThat(this.lifecycle.concerns(routes)).isTrue();
		assertThat(this.lifecycle.concerns(directory.resolve("gateway.log"))).isFalse();
		assertThat(this.lifecycle.concerns(directory.resolve("routes.yaml.swp"))).isFalse();
	}

	@Test
	void aFileThatBecomesARouteFileConcernsTheWatcher(@TempDir Path directory) throws Exception {
		write(directory.resolve("routes.yaml"), "orders");
		start(directory);
		Path more = directory.resolve("more.yaml");
		assertThat(this.lifecycle.concerns(more)).isFalse();

		Files.writeString(more, "routes: []");

		assertThat(this.lifecycle.concerns(more)).isTrue();
	}

	@Test
	void aRouteFileThatWasDeletedStillConcernsTheWatcher(@TempDir Path directory) throws Exception {
		write(directory.resolve("routes.yaml"), "orders");
		Path more = directory.resolve("more.yaml");
		write(more, "invoices");
		FileRouteDefinitionLoader loader = new FileRouteDefinitionLoader(new RouteDefinitionFileParser(),
				List.of("file:" + directory + "/*.yaml"), new PathMatchingResourcePatternResolver());
		this.locator = new FileRouteDefinitionLocator(loader, (event) -> {
		});
		// Not watching for real: the reload a deletion triggers would forget the file.
		this.lifecycle = new RouteFilesLifecycle(this.locator, loader, false);
		this.lifecycle.start();

		Files.delete(more);

		assertThat(this.lifecycle.concerns(more)).isTrue();
	}

	@Test
	void aRouteFileIsStillReloadedWhileAnotherFileKeepsBeingWritten(@TempDir Path directory) throws Exception {
		Path routes = directory.resolve("routes.yaml");
		write(routes, "orders");
		start(directory);
		Thread noise = new Thread(() -> keepWriting(directory.resolve("gateway.log")));
		noise.setDaemon(true);
		noise.start();
		Thread.sleep(300);

		write(routes, "invoices");

		assertThat(awaitRouteIds("invoices")).containsExactly("invoices");
	}

	@Test
	void aRouteFileCreatedLaterIsStillPickedUp(@TempDir Path directory) throws Exception {
		write(directory.resolve("routes.yaml"), "orders");
		start(directory);

		write(directory.resolve("more.yaml"), "invoices");

		assertThat(awaitRouteIds("invoices")).containsExactlyInAnyOrder("orders", "invoices");
	}

	@Test
	void aRouteFileDeletedTakesItsRoutesAway(@TempDir Path directory) throws Exception {
		write(directory.resolve("routes.yaml"), "orders");
		Path more = directory.resolve("more.yaml");
		write(more, "invoices");
		start(directory);
		assertThat(routeIds()).containsExactlyInAnyOrder("orders", "invoices");

		Files.delete(more);

		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (routeIds().contains("invoices") && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
		assertThat(routeIds()).containsExactly("orders");
	}

	private void start(Path directory) {
		FileRouteDefinitionLoader loader = new FileRouteDefinitionLoader(new RouteDefinitionFileParser(),
				List.of("file:" + directory + "/*.yaml"), new PathMatchingResourcePatternResolver()) {
			@Override
			public List<RouteDefinition> load() {
				RouteFilesLifecycleUnrelatedFilesTests.this.loads.incrementAndGet();
				return super.load();
			}
		};
		this.locator = new FileRouteDefinitionLocator(loader, (event) -> {
		});
		this.lifecycle = new RouteFilesLifecycle(this.locator, loader, true);
		this.lifecycle.start();
	}

	private void keepWriting(Path file) {
		int line = 0;
		while (this.writing.get()) {
			try {
				Files.writeString(file, "line " + (line++));
				Thread.sleep(50);
			}
			catch (IOException ex) {
				return;
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private List<String> awaitRouteIds(String expected) throws InterruptedException {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (!routeIds().contains(expected) && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
		return routeIds();
	}

	private List<String> routeIds() {
		return this.locator.getRouteDefinitions().map(RouteDefinition::getId).collectList().block();
	}

	private static void write(Path file, String routeId) throws IOException {
		Files.writeString(file, String.join("\n", "routes:", "  - id: " + routeId, "    uri: https://example.org",
				"    predicates:", "      - Path=/api/**", ""));
	}

}
