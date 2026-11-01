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

package ch.nexsol.gateway.ui.security;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * What a routed service actually receives: the cookies of the browser, minus the one the
 * console signed the operator in with.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT,
		properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
				"spring.cloud.gateway.server.webflux.ui.security.user.password=console-secret" })
class UiSessionCookieStripperIntegrationTests {

	private static final AtomicReference<String> RECEIVED = new AtomicReference<>();

	private static DisposableServer backend;

	@LocalServerPort
	int port;

	@BeforeAll
	static void startBackend() {
		backend = HttpServer.create().port(0).handle((request, response) -> {
			RECEIVED.set(request.requestHeaders().get(HttpHeaders.COOKIE));
			return response.sendString(Mono.just("served"));
		}).bindNow();
	}

	@AfterAll
	static void stopBackend() {
		backend.disposeNow();
	}

	@DynamicPropertySource
	static void backendRoute(DynamicPropertyRegistry registry) {
		registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "backend");
		registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", () -> "http://localhost:" + backend.port());
		registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/api/**");
	}

	@Test
	void keepsTheCookieOfTheConsoleOutOfTheRoutedRequest() {
		RECEIVED.set(null);

		client().get()
			.uri("/api/orders")
			.header(HttpHeaders.COOKIE,
					"locale=fr; " + UiSessionCookieName.COOKIE_NAME + "=an-operator; SESSION=the-app")
			.exchange()
			.expectStatus()
			.isOk();

		assertThat(RECEIVED.get()).isEqualTo("locale=fr; SESSION=the-app");
	}

	@Test
	void handsOverTheCookiesTheServiceIsEntitledTo() {
		RECEIVED.set(null);

		client().get()
			.uri("/api/orders")
			.header(HttpHeaders.COOKIE, "SESSION=the-app")
			.exchange()
			.expectStatus()
			.isOk();

		assertThat(RECEIVED.get()).isEqualTo("SESSION=the-app");
	}

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

}
