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

package ch.nexsol.gateway.audit.factory;

import java.net.ConnectException;

import ch.nexsol.gateway.audit.AuditAttributes;
import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventFactory;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.AuditProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AuditGatewayFilterFactoryTests {

	private final AuditEventFactory eventFactory = new AuditEventFactory(new AuditProperties());

	@Test
	void auditsResponseAfterChainCompletes() {
		AuditEventPublisher publisher = mock(AuditEventPublisher.class);
		GatewayFilter filter = new AuditGatewayFilterFactory(this.eventFactory, publisher)
			.apply(new AuditGatewayFilterFactory.Config());
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/book").build());
		GatewayFilterChain chain = (ex) -> {
			ex.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
			return Mono.empty();
		};

		StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

		ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
		verify(publisher, times(1)).publish(captor.capture());
		assertThat(captor.getValue().attributes()).containsEntry(AuditAttributes.RESPONSE_STATUS, "NOT_FOUND");
	}

	@Test
	void auditsAndPropagatesWhenTheChainFails() {
		AuditEventPublisher publisher = mock(AuditEventPublisher.class);
		GatewayFilter filter = new AuditGatewayFilterFactory(this.eventFactory, publisher)
			.apply(new AuditGatewayFilterFactory.Config());
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/book").build());
		ConnectException failure = new ConnectException("connection refused");

		StepVerifier.create(filter.filter(exchange, (ex) -> Mono.error(failure)))
			.verifyErrorMatches((ex) -> ex == failure);

		ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
		verify(publisher, times(1)).publish(captor.capture());
		assertThat(captor.getValue().attributes()).containsEntry(AuditAttributes.REQUEST_PATH, "/book");
	}

	@Test
	void swallowsPublisherError() {
		AuditEventPublisher publisher = mock(AuditEventPublisher.class);
		doThrow(new RuntimeException("boom")).when(publisher).publish(any());
		GatewayFilter filter = new AuditGatewayFilterFactory(this.eventFactory, publisher)
			.apply(new AuditGatewayFilterFactory.Config());
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/book").build());

		StepVerifier.create(filter.filter(exchange, (ex) -> Mono.empty())).verifyComplete();

		verify(publisher).publish(any());
	}

}
