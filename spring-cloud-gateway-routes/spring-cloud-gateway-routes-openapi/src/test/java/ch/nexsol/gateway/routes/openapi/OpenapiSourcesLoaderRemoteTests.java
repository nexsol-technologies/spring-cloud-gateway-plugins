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

package ch.nexsol.gateway.routes.openapi;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how a sources document served over HTTP is read: once, and never without a
 * deadline. The sources are read on every poll, one tick at a time, and once more at
 * startup: a server that accepts the connection and says nothing would hold both for
 * ever.
 */
class OpenapiSourcesLoaderRemoteTests {

	private static final String DOCUMENT = "{\"sources\": [{\"id\": \"partner\", \"uri\": \"https://partner.example.org\", "
			+ "\"spec-url\": \"https://partner.example.org/v3/api-docs\"}]}";

	@Test
	void readsARemoteDocumentWithASingleRequest() throws IOException {
		List<String> methods = new CopyOnWriteArrayList<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/sources.json", (exchange) -> {
			methods.add(exchange.getRequestMethod());
			byte[] body = DOCUMENT.getBytes(StandardCharsets.UTF_8);
			boolean head = "HEAD".equals(exchange.getRequestMethod());
			exchange.sendResponseHeaders(200, head ? -1 : body.length);
			if (!head) {
				exchange.getResponseBody().write(body);
			}
			exchange.close();
		});
		server.start();
		try {
			List<Source> sources = loader("http://localhost:" + server.getAddress().getPort() + "/sources.json",
					Duration.ofSeconds(5))
				.load();

			assertThat(sources).extracting(Source::getId).containsExactly("partner");
			assertThat(methods).containsExactly("GET");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void givesUpOnAServerThatAcceptsTheConnectionAndNeverAnswers() throws Exception {
		try (ServerSocket silent = new ServerSocket(0)) {
			OpenapiSourcesLoader loader = loader("http://localhost:" + silent.getLocalPort() + "/sources.json",
					Duration.ofMillis(300));

			List<Source> sources = CompletableFuture.supplyAsync(loader::load).get(5, TimeUnit.SECONDS);

			assertThat(sources).isEmpty();
		}
	}

	@Test
	void skipsARemoteDocumentThatIsNotThere() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.start();
		try {
			List<Source> sources = loader("http://localhost:" + server.getAddress().getPort() + "/missing.json",
					Duration.ofSeconds(5))
				.load();

			assertThat(sources).isEmpty();
		}
		finally {
			server.stop(0);
		}
	}

	private static OpenapiSourcesLoader loader(String location, Duration readTimeout) {
		RoutesOpenapiProperties properties = new RoutesOpenapiProperties();
		properties.setSourcesLocations(List.of(location));
		return new OpenapiSourcesLoader(properties, new PathMatchingResourcePatternResolver(), Duration.ofSeconds(5),
				readTimeout);
	}

}
