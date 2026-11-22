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

package ch.nexsol.gateway.audit;

import java.net.ConnectException;
import java.util.List;

import ch.nexsol.gateway.audit.factory.AuditGatewayFilterFactory;
import ch.nexsol.gateway.audit.webfilter.AuditWebFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests the status audited for an exchange that ended in an error. The exception handler
 * writes the status after the filters have unwound, so at the time the event is built the
 * response does not carry it yet: the status has to come from the failure.
 */
class AuditFailureStatusTests {

	private final AuditEventFactory eventFactory = new AuditEventFactory(new AuditProperties());

	@Test
	void webFilterAuditsTheStatusARejectionIsAnsweredWith() {
		assertThat(auditedByTheWebFilter(new ResponseStatusException(HttpStatus.FORBIDDEN))).isEqualTo("FORBIDDEN");
	}

	@Test
	void webFilterAuditsAServerErrorForAFailureThatNamesNoStatus() {
		assertThat(auditedByTheWebFilter(new ConnectException("connection refused")))
			.isEqualTo("INTERNAL_SERVER_ERROR");
	}

	@Test
	void routeFilterAuditsTheStatusARejectionIsAnsweredWith() {
		assertThat(auditedByTheRouteFilter(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT)))
			.isEqualTo("GATEWAY_TIMEOUT");
	}

	@Test
	void routeFilterAuditsAServerErrorForAFailureThatNamesNoStatus() {
		assertThat(auditedByTheRouteFilter(new ConnectException("connection refused")))
			.isEqualTo("INTERNAL_SERVER_ERROR");
	}

	@Test
	void aResponseAlreadySentKeepsItsStatusWhateverFailsAfterwards() {
		AuditEventPublisher publisher = mock(AuditEventPublisher.class);
		AuditWebFilter filter = new AuditWebFilter(this.eventFactory, publisher, List.of());
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/book").build());
		ConnectException failure = new ConnectException("connection reset while streaming");

		StepVerifier.create(filter.filter(exchange, (ex) -> {
			ex.getResponse().setStatusCode(HttpStatus.OK);
			return ex.getResponse().setComplete().then(Mono.error(failure));
		})).verifyErrorMatches((ex) -> ex == failure);

		assertThat(status(publisher)).isEqualTo("OK");
	}

	private String auditedByTheWebFilter(Exception failure) {
		AuditEventPublisher publisher = mock(AuditEventPublisher.class);
		AuditWebFilter filter = new AuditWebFilter(this.eventFactory, publisher, List.of());
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/book").build());

		StepVerifier.create(filter.filter(exchange, (ex) -> Mono.error(failure)))
			.verifyErrorMatches((ex) -> ex == failure);

		return status(publisher);
	}

	private String auditedByTheRouteFilter(Exception failure) {
		AuditEventPublisher publisher = mock(AuditEventPublisher.class);
		AuditGatewayFilterFactory factory = new AuditGatewayFilterFactory(this.eventFactory, publisher);
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/book").build());

		StepVerifier
			.create(factory.apply(new AuditGatewayFilterFactory.Config()).filter(exchange, (ex) -> Mono.error(failure)))
			.verifyErrorMatches((ex) -> ex == failure);

		return status(publisher);
	}

	private static String status(AuditEventPublisher publisher) {
		ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
		verify(publisher).publish(captor.capture());
		return captor.getValue().attributes().get(AuditAttributes.RESPONSE_STATUS);
	}

}
