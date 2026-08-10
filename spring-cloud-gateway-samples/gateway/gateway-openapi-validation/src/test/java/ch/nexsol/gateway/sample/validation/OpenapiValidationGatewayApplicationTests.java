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

package ch.nexsol.gateway.sample.validation;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code OpenapiValidation} filter through the whole gateway, on the
 * verdicts it reaches without any backend: a request breaking the contract is denied
 * before it is ever forwarded.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class OpenapiValidationGatewayApplicationTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldRejectAParameterThatIsNotTheDeclaredType() {
		this.webTestClient.get().uri("/book-service/books?page=first").exchange().expectStatus().isBadRequest();
	}

	@Test
	void shouldRejectAParameterOutsideItsDeclaredEnumeration() {
		this.webTestClient.get().uri("/book-service/books?status=lost").exchange().expectStatus().isBadRequest();
	}

	@Test
	void shouldRejectAPathTheContractDoesNotDeclare() {
		this.webTestClient.get().uri("/book-service/authors").exchange().expectStatus().isBadRequest();
	}

	@Test
	void shouldRejectABodyMissingARequiredProperty() {
		this.webTestClient.post()
			.uri("/book-service/books")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"title\":\"Dune\"}")
			.exchange()
			.expectStatus()
			.isBadRequest();
	}

	/**
	 * The forwarded case cannot be asserted on its response, which depends on a backend
	 * the sample does not start. What it does assert is that the filter let the request
	 * through: it never answered {@code 400} itself.
	 */
	@Test
	void shouldForwardARequestThatHonoursTheContract() {
		this.webTestClient.get()
			.uri("/book-service/books?page=1&status=available")
			.exchange()
			.expectStatus()
			.value((status) -> assertThat(status).isNotEqualTo(400));
	}

	@Test
	void shouldForwardABodyThatHonoursTheContract() {
		this.webTestClient.post()
			.uri("/book-service/books")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"title\":\"Dune\",\"author\":\"Herbert\"}")
			.exchange()
			.expectStatus()
			.value((status) -> assertThat(status).isNotEqualTo(400));
	}

}
