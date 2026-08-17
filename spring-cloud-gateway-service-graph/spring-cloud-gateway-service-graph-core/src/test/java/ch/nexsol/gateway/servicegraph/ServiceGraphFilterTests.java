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

package ch.nexsol.gateway.servicegraph;

import java.util.List;

import ch.nexsol.gateway.servicegraph.ServiceGraphProperties.Caller;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.CALLER_TAG;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.CALLS_METER;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.OUTCOME_TAG;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.ROUTE_TAG;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.SERVICE_TAG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Tests for {@link ServiceGraphFilter}.
 */
class ServiceGraphFilterTests {

	private static final GatewayFilterChain EMPTY_CHAIN = (exchange) -> Mono.empty();

	private SimpleMeterRegistry registry;

	private ServiceGraphFilter filter;

	@BeforeEach
	void setUp() {
		this.registry = new SimpleMeterRegistry();
		this.filter = filter(this.registry);
	}

	@Test
	void countsOneCallFromTheCallerToTheServiceItReached() {
		MockServerWebExchange exchange = routed("orders-route", HttpStatus.OK);

		this.filter.filter(exchange, EMPTY_CHAIN).block();

		assertThat(counter("partner-a", "orders-route", ServiceGraphFilter.SUCCESS)).isEqualTo(1.0);
	}

	@Test
	void keepsTheOutcomeOfTheCallApart() {
		this.filter.filter(routed("orders-route", HttpStatus.NOT_FOUND), EMPTY_CHAIN).block();
		this.filter.filter(routed("orders-route", HttpStatus.BAD_GATEWAY), EMPTY_CHAIN).block();

		assertThat(counter("partner-a", "orders-route", ServiceGraphFilter.CLIENT_ERROR)).isEqualTo(1.0);
		assertThat(counter("partner-a", "orders-route", ServiceGraphFilter.SERVER_ERROR)).isEqualTo(1.0);
	}

	@Test
	void reportsAnUnknownOutcomeWhenNoStatusWasWritten() {
		MockServerWebExchange exchange = routed("orders-route", null);

		this.filter.filter(exchange, EMPTY_CHAIN).block();

		assertThat(counter("partner-a", "orders-route", ServiceGraphFilter.UNKNOWN_OUTCOME)).isEqualTo(1.0);
	}

	@Test
	void countsTheCallAndPropagatesTheFailureWhenTheChainFails() {
		MockServerWebExchange exchange = routed("orders-route", HttpStatus.INTERNAL_SERVER_ERROR);
		GatewayFilterChain failing = (ignored) -> Mono.error(new IllegalStateException("upstream refused"));

		StepVerifier.create(this.filter.filter(exchange, failing)).expectError(IllegalStateException.class).verify();

		assertThat(counter("partner-a", "orders-route", ServiceGraphFilter.SERVER_ERROR)).isEqualTo(1.0);
	}

	@Test
	void countsNothingWhenNoRouteServedTheExchange() {
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/orders").header("X-Caller", "partner-a"));

		this.filter.filter(exchange, EMPTY_CHAIN).block();

		assertThat(this.registry.find(CALLS_METER).counters()).isEmpty();
	}

	@Test
	void countsNothingForAnExcludedRoute() {
		this.filter.filter(routed("openapi-docs-orders", HttpStatus.OK), EMPTY_CHAIN).block();

		assertThat(this.registry.find(CALLS_METER).counters()).isEmpty();
	}

	@Test
	void drawsEveryRouteWhenTheExclusionsAreEmptied() {
		ServiceGraphProperties properties = new ServiceGraphProperties();
		properties.setExcludedRoutes(List.of());
		ServiceGraphFilter drawing = filter(this.registry, properties);

		drawing.filter(routed("openapi-docs-orders", HttpStatus.OK), EMPTY_CHAIN).block();

		assertThat(counter("partner-a", "openapi-docs-orders", ServiceGraphFilter.SUCCESS)).isEqualTo(1.0);
	}

	@Test
	void countsNothingWhenTheRouteHasNoId() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));
		exchange.getAttributes()
			.put(GATEWAY_ROUTE_ATTR, Route.async().id("").uri("http://upstream").predicate((it) -> true).build());

		this.filter.filter(exchange, EMPTY_CHAIN).block();

		assertThat(this.registry.find(CALLS_METER).counters()).isEmpty();
	}

	@Test
	void servesTheExchangeWhenTheApplicationPublishesNoMetrics() {
		ServiceGraphFilter unregistered = filter(null);
		MockServerWebExchange exchange = routed("orders-route", HttpStatus.OK);

		StepVerifier.create(unregistered.filter(exchange, EMPTY_CHAIN)).verifyComplete();
	}

	@Test
	void runsFirstSoTheCallIsCountedAroundTheWholeChain() {
		assertThat(this.filter.getOrder()).isEqualTo(Integer.MIN_VALUE);
	}

	@Test
	void namesALoadBalancedTargetAfterItsServiceId() {
		assertThat(ServiceGraphFilter.targetService(route("orders-route", "lb://orders"))).isEqualTo("orders");
	}

	@Test
	void keepsThePortWhenTheTargetCarriesOne() {
		assertThat(ServiceGraphFilter.targetService(route("orders-route", "http://upstream:8080")))
			.isEqualTo("upstream:8080");
	}

	@Test
	void fallsBackToTheRouteIdWhenTheTargetHasNoHost() {
		assertThat(ServiceGraphFilter.targetService(route("local-route", "forward:/local"))).isEqualTo("local-route");
	}

	private static Route route(String routeId, String uri) {
		return Route.async().id(routeId).uri(uri).predicate((it) -> true).build();
	}

	private static ServiceGraphFilter filter(MeterRegistry registry) {
		return filter(registry, new ServiceGraphProperties());
	}

	private static ServiceGraphFilter filter(MeterRegistry registry, ServiceGraphProperties properties) {
		Caller caller = properties.getCaller();
		caller.setHeader("X-Caller");
		@SuppressWarnings("unchecked")
		ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(registry);
		return new ServiceGraphFilter(provider, new CallerResolver(caller), properties);
	}

	private static MockServerWebExchange routed(String routeId, HttpStatus status) {
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/orders").header("X-Caller", "partner-a"));
		exchange.getAttributes()
			.put(GATEWAY_ROUTE_ATTR, Route.async().id(routeId).uri("http://upstream").predicate((it) -> true).build());
		exchange.getResponse().setStatusCode(status);
		return exchange;
	}

	private double counter(String caller, String route, String outcome) {
		return this.registry.find(CALLS_METER)
			.tag(CALLER_TAG, caller)
			.tag(SERVICE_TAG, "upstream")
			.tag(ROUTE_TAG, route)
			.tag(OUTCOME_TAG, outcome)
			.counter()
			.count();
	}

}
