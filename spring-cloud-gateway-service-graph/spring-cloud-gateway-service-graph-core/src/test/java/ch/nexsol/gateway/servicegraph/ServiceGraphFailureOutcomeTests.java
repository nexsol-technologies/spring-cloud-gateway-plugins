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

import java.net.ConnectException;
import java.util.List;

import ch.nexsol.gateway.servicegraph.ServiceGraphProperties.Caller;
import io.micrometer.core.instrument.Counter;
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
import org.springframework.web.server.ResponseStatusException;

import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.CALLS_METER;
import static ch.nexsol.gateway.servicegraph.ServiceGraphFilter.OUTCOME_TAG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Tests the outcome counted for a call that ended in an error. The exception handler
 * writes the status after the filters have unwound, so when the call is counted the
 * response does not carry it yet: the outcome has to come from the failure.
 */
class ServiceGraphFailureOutcomeTests {

	private SimpleMeterRegistry registry;

	private ServiceGraphFilter filter;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.registry = new SimpleMeterRegistry();
		ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.registry);
		ServiceGraphProperties properties = new ServiceGraphProperties();
		Caller caller = properties.getCaller();
		caller.setHeader("X-Caller");
		this.filter = new ServiceGraphFilter(provider, new CallerResolver(caller), properties);
	}

	@Test
	void countsAServerErrorForAnUpstreamThatTimedOut() {
		assertThat(outcomeOf(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT)))
			.isEqualTo(ServiceGraphFilter.SERVER_ERROR);
	}

	@Test
	void countsAServerErrorForAFailureThatNamesNoStatus() {
		assertThat(outcomeOf(new ConnectException("connection refused"))).isEqualTo(ServiceGraphFilter.SERVER_ERROR);
	}

	@Test
	void countsAClientErrorForARequestTheGatewayRejected() {
		assertThat(outcomeOf(new ResponseStatusException(HttpStatus.FORBIDDEN)))
			.isEqualTo(ServiceGraphFilter.CLIENT_ERROR);
	}

	@Test
	void aResponseAlreadySentKeepsItsOutcomeWhateverFailsAfterwards() {
		MockServerWebExchange exchange = routed();
		ConnectException failure = new ConnectException("connection reset while streaming");
		GatewayFilterChain streaming = (answered) -> {
			answered.getResponse().setStatusCode(HttpStatus.OK);
			return answered.getResponse().setComplete().then(Mono.error(failure));
		};

		StepVerifier.create(this.filter.filter(exchange, streaming)).verifyErrorMatches((ex) -> ex == failure);

		assertThat(countedOutcomes()).containsExactly(ServiceGraphFilter.SUCCESS);
	}

	private String outcomeOf(Exception failure) {
		StepVerifier.create(this.filter.filter(routed(), (ignored) -> Mono.error(failure)))
			.verifyErrorMatches((ex) -> ex == failure);
		return countedOutcomes().get(0);
	}

	private List<String> countedOutcomes() {
		return this.registry.find(CALLS_METER)
			.counters()
			.stream()
			.filter((counter) -> counter.count() > 0)
			.map(Counter::getId)
			.map((id) -> id.getTag(OUTCOME_TAG))
			.toList();
	}

	private static MockServerWebExchange routed() {
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/orders").header("X-Caller", "partner-a"));
		exchange.getAttributes()
			.put(GATEWAY_ROUTE_ATTR,
					Route.async().id("orders-route").uri("lb://orders").predicate((ignored) -> true).build());
		return exchange;
	}

}
