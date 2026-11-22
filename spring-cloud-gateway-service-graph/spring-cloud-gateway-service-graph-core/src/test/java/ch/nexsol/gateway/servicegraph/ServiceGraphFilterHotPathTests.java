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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.CALLS_METER;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.OUTCOME_TAG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Tests what the filter does for every routed request, which has to stay next to nothing:
 * the registry is not a lookup in the bean factory per request, and an edge already
 * counted is not a meter to find again.
 */
class ServiceGraphFilterHotPathTests {

	private static final GatewayFilterChain EMPTY_CHAIN = (exchange) -> Mono.empty();

	private MeterRegistry registry;

	private ObjectProvider<MeterRegistry> provider;

	private ServiceGraphFilter filter;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.registry = spy(new SimpleMeterRegistry());
		this.provider = mock(ObjectProvider.class);
		when(this.provider.getIfAvailable()).thenReturn(this.registry);
		ServiceGraphProperties properties = new ServiceGraphProperties();
		properties.getCaller().setHeader("X-Caller");
		this.filter = new ServiceGraphFilter(this.provider, new CallerResolver(properties.getCaller()), properties);
	}

	@Test
	void resolvesTheRegistryOnceRatherThanForEveryRequest() {
		for (int i = 0; i < 3; i++) {
			this.filter.filter(routed(HttpStatus.OK), EMPTY_CHAIN).block();
		}

		verify(this.provider, times(1)).getIfAvailable();
	}

	@Test
	void findsTheCounterOfAnEdgeOnceAndKeepsCountingOnIt() {
		for (int i = 0; i < 3; i++) {
			this.filter.filter(routed(HttpStatus.OK), EMPTY_CHAIN).block();
		}

		verify(this.registry, times(1)).counter(eq(CALLS_METER), any(String[].class));
		assertThat(calls(ServiceGraphFilter.SUCCESS)).isEqualTo(3.0);
	}

	@Test
	void keepsACounterPerOutcome() {
		this.filter.filter(routed(HttpStatus.OK), EMPTY_CHAIN).block();
		this.filter.filter(routed(HttpStatus.BAD_GATEWAY), EMPTY_CHAIN).block();
		this.filter.filter(routed(HttpStatus.OK), EMPTY_CHAIN).block();

		assertThat(calls(ServiceGraphFilter.SUCCESS)).isEqualTo(2.0);
		assertThat(calls(ServiceGraphFilter.SERVER_ERROR)).isEqualTo(1.0);
	}

	@Test
	@SuppressWarnings("unchecked")
	void picksUpARegistryThatWasNotThereForTheFirstRequests() {
		ObjectProvider<MeterRegistry> late = mock(ObjectProvider.class);
		when(late.getIfAvailable()).thenReturn(null, this.registry);
		ServiceGraphProperties properties = new ServiceGraphProperties();
		properties.getCaller().setHeader("X-Caller");
		ServiceGraphFilter starting = new ServiceGraphFilter(late, new CallerResolver(properties.getCaller()),
				properties);

		starting.filter(routed(HttpStatus.OK), EMPTY_CHAIN).block();
		starting.filter(routed(HttpStatus.OK), EMPTY_CHAIN).block();

		assertThat(calls(ServiceGraphFilter.SUCCESS)).isEqualTo(1.0);
	}

	private double calls(String outcome) {
		return this.registry.find(CALLS_METER).tag(OUTCOME_TAG, outcome).counter().count();
	}

	private static MockServerWebExchange routed(HttpStatus status) {
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/orders").header("X-Caller", "partner-a"));
		exchange.getAttributes()
			.put(GATEWAY_ROUTE_ATTR,
					Route.async().id("orders-route").uri("http://upstream").predicate((it) -> true).build());
		exchange.getResponse().setStatusCode(status);
		return exchange;
	}

}
