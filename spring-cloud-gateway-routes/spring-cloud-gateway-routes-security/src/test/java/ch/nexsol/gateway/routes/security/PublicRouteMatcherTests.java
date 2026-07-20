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

import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;
import org.springframework.web.server.ServerWebExchange;

/**
 * Unit tests for {@link PublicRouteMatcher}.
 */
class PublicRouteMatcherTests {

	private static final Predicate<ServerWebExchange> ALWAYS = (exchange) -> true;

	private static final Predicate<ServerWebExchange> NEVER = (exchange) -> false;

	@Test
	void matchesWhenTargetRouteIsFlaggedPublic() {
		PublicRouteMatcher matcher = new PublicRouteMatcher(
				locator(route("public", ALWAYS, true), route("private", ALWAYS, false)));

		StepVerifier.create(matcher.matches(exchange()).map(MatchResult::isMatch)).expectNext(true).verifyComplete();
	}

	@Test
	void doesNotMatchWhenTargetRouteIsNotPublic() {
		PublicRouteMatcher matcher = new PublicRouteMatcher(locator(route("private", ALWAYS, false)));

		StepVerifier.create(matcher.matches(exchange()).map(MatchResult::isMatch)).expectNext(false).verifyComplete();
	}

	@Test
	void doesNotMatchWhenNoRouteMatchesTheExchange() {
		PublicRouteMatcher matcher = new PublicRouteMatcher(locator(route("public", NEVER, true)));

		StepVerifier.create(matcher.matches(exchange()).map(MatchResult::isMatch)).expectNext(false).verifyComplete();
	}

	@Test
	void usesTheFirstMatchingRouteWhenAPublicOneWouldMatchLater() {
		// The first (private) route matches, so the public one behind it is irrelevant.
		PublicRouteMatcher matcher = new PublicRouteMatcher(
				locator(route("private", ALWAYS, false), route("public", ALWAYS, true)));

		StepVerifier.create(matcher.matches(exchange()).map(MatchResult::isMatch)).expectNext(false).verifyComplete();
	}

	private static ServerWebExchange exchange() {
		return MockServerWebExchange.from(MockServerHttpRequest.get("/anything").build());
	}

	private static RouteLocator locator(Route... routes) {
		return () -> Flux.just(routes);
	}

	private static Route route(String id, Predicate<ServerWebExchange> predicate, boolean isPublic) {
		return Route.async()
			.id(id)
			.uri("http://localhost:9000")
			.predicate(predicate)
			.metadata(PublicRouteMatcher.PUBLIC_METADATA_KEY, isPublic)
			.build();
	}

}
