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

package ch.nexsol.gateway.commons;

import java.net.ConnectException;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ExchangeStatus}.
 */
class ExchangeStatusTests {

	@Test
	void readsTheStatusOfTheResponseWhenTheChainCompleted() {
		MockServerWebExchange exchange = exchange();
		exchange.getResponse().setStatusCode(HttpStatus.CREATED);

		assertThat(ExchangeStatus.of(exchange, null)).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void reportsNoStatusWhenTheChainCompletedWithoutWritingOne() {
		assertThat(ExchangeStatus.of(exchange(), null)).isNull();
	}

	@Test
	void readsTheStatusAFailureCarries() {
		assertThat(ExchangeStatus.of(exchange(), new ResponseStatusException(HttpStatus.FORBIDDEN)))
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void readsTheStatusAFailureIsAnnotatedWith() {
		assertThat(ExchangeStatus.of(exchange(), new QuotaExceededException())).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	void answersAServerErrorForAFailureThatNamesNoStatus() {
		assertThat(ExchangeStatus.of(exchange(), new ConnectException("connection refused")))
			.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	void theFailureWinsOverAStatusWrittenButNotSent() {
		MockServerWebExchange exchange = exchange();
		exchange.getResponse().setStatusCode(HttpStatus.OK);

		assertThat(ExchangeStatus.of(exchange, new ResponseStatusException(HttpStatus.BAD_GATEWAY)))
			.isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	@Test
	void aResponseAlreadySentKeepsItsStatus() {
		MockServerWebExchange exchange = exchange();
		exchange.getResponse().setStatusCode(HttpStatus.OK);
		exchange.getResponse().setComplete().block();

		assertThat(ExchangeStatus.of(exchange, new ConnectException("connection reset"))).isEqualTo(HttpStatus.OK);
	}

	private static MockServerWebExchange exchange() {
		return MockServerWebExchange.from(MockServerHttpRequest.get("/book").build());
	}

	@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
	static class QuotaExceededException extends RuntimeException {

	}

}
