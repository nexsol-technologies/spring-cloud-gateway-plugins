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

package ch.nexsol.gateway.routes.security;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Tests how much of the route table the public-routes chain replays for a request. Its
 * matcher is the first one Spring Security asks, for every request: replaying the
 * predicate of every route there matches the routes a second time, ahead of the gateway
 * doing it for real.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, classes = PublicRouteScanTests.Application.class)
class PublicRouteScanTests {

	private static final AtomicInteger PRIVATE_PREDICATE_CALLS = new AtomicInteger();

	private static final GatewayFilter ANSWERING = (exchange, chain) -> {
		exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
		return exchange.getResponse().setComplete();
	};

	@LocalServerPort
	int port;

	@Autowired
	Routes routes;

	@Autowired
	ApplicationEventPublisher publisher;

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void replaysNoPredicateWhenNoRouteIsPublic() {
		declare(privateRoute());

		client().get().uri("/private/thing").exchange().expectStatus().isUnauthorized();

		assertThat(PRIVATE_PREDICATE_CALLS).hasValue(0);
	}

	@Test
	void doesNotReplayTheRoutesBehindTheLastPublicOne() {
		declare(publicRoute("/open/"), privateRoute());

		client().get().uri("/open/thing").exchange().expectStatus().isEqualTo(HttpStatus.NO_CONTENT);
		client().get().uri("/private/thing").exchange().expectStatus().isUnauthorized();

		assertThat(PRIVATE_PREDICATE_CALLS).hasValue(0);
	}

	@Test
	void stillLetsTheFirstMatchingRouteDecide() {
		// The private route is ahead of a public one covering the same path: it serves
		// the request, so the request is not public.
		declare(privateRoute(), publicRoute("/private/"));

		client().get().uri("/private/thing").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void followsTheRouteTableWhenItChanges() {
		declare(privateRoute());
		client().get().uri("/late/thing").exchange().expectStatus().isUnauthorized();

		declare(privateRoute(), publicRoute("/late/"));
		client().get().uri("/late/thing").exchange().expectStatus().isEqualTo(HttpStatus.NO_CONTENT);

		declare(privateRoute());
		client().get().uri("/late/thing").exchange().expectStatus().isUnauthorized();
	}

	private void declare(Route... declared) {
		this.routes.current.clear();
		this.routes.current.addAll(List.of(declared));
		this.publisher.publishEvent(new RefreshRoutesEvent(this));
		PRIVATE_PREDICATE_CALLS.set(0);
	}

	private static Route privateRoute() {
		return Route.async().id("private").uri("no://op").predicate((exchange) -> {
			PRIVATE_PREDICATE_CALLS.incrementAndGet();
			return exchange.getRequest().getPath().value().startsWith("/private/");
		}).filter(ANSWERING).build();
	}

	private static Route publicRoute(String prefix) {
		return Route.async()
			.id("public" + prefix)
			.uri("no://op")
			.predicate((exchange) -> exchange.getRequest().getPath().value().startsWith(prefix))
			.filter(ANSWERING)
			.metadata(PublicRouteMatcher.PUBLIC_METADATA_KEY, true)
			.build();
	}

	/**
	 * The routes of the gateway, declared by each test.
	 */
	static class Routes implements RouteLocator {

		final List<Route> current = new CopyOnWriteArrayList<>();

		@Override
		public Flux<Route> getRoutes() {
			return Flux.fromIterable(this.current);
		}

	}

	/**
	 * A gateway that authenticates everything, over routes each test declares.
	 */
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class Application {

		@Bean
		SecurityWebFilterChain applicationChain(ServerHttpSecurity http) {
			http.csrf(ServerHttpSecurity.CsrfSpec::disable);
			http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
			http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
			http.authorizeExchange((spec) -> spec.anyExchange().authenticated());
			return http.build();
		}

		@Bean
		Routes routes() {
			return new Routes();
		}

	}

}
