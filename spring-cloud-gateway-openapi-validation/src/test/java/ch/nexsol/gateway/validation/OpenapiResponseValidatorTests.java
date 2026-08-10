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

package ch.nexsol.gateway.validation;

import java.nio.charset.StandardCharsets;

import ch.nexsol.gateway.validation.OpenapiContract.Resolution;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what the response side of the contract accepts: the status, the headers it
 * requires, and the body.
 */
class OpenapiResponseValidatorTests {

	private final OpenapiContract contract = Contracts.bookstore();

	private final OpenapiResponseValidator validator = new OpenapiResponseValidator();

	@Test
	void acceptsAResponseThatHonoursTheContract() {
		ValidationReport report = validate("/books/7", HttpMethod.GET, 200, jsonHeaders(),
				"{\"title\":\"Dune\",\"author\":\"Herbert\"}");

		assertThat(report.isValid()).isTrue();
	}

	@Test
	void rejectsAStatusTheContractDoesNotDeclare() {
		ValidationReport report = validate("/books/7", HttpMethod.GET, 418, jsonHeaders(), "{}");

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("response status 418 is not declared");
	}

	@Test
	void rejectsABodyThatBreaksTheDeclaredSchema() {
		ValidationReport report = validate("/books/7", HttpMethod.GET, 200, jsonHeaders(),
				"{\"title\":\"Dune\",\"pages\":0}");

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("response body");
	}

	@Test
	void rejectsAMissingHeaderTheContractRequires() {
		Resolution.Found found = found("/books", HttpMethod.POST);

		ValidationReport report = this.validator.validate(this.contract, "POST /books", found, 201, new HttpHeaders(),
				null);

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("response header 'Location'").contains("is missing");
	}

	@Test
	void acceptsAResponseCarryingTheHeaderTheContractRequires() {
		Resolution.Found found = found("/books", HttpMethod.POST);
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.LOCATION, "/books/7");

		ValidationReport report = this.validator.validate(this.contract, "POST /books", found, 201, headers, null);

		assertThat(report.isValid()).isTrue();
	}

	@Test
	void checksTheStatusEvenWhenTheBodyWasNotRead() {
		Resolution.Found found = found("/books/7", HttpMethod.GET);

		ValidationReport report = this.validator.validate(this.contract, "GET /books/{id}", found, 500, jsonHeaders(),
				null);

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("response status 500 is not declared");
	}

	@Test
	void acceptsAStatusDeclaredWithoutABody() {
		Resolution.Found found = found("/books/7", HttpMethod.GET);

		ValidationReport report = this.validator.validate(this.contract, "GET /books/{id}", found, 404,
				new HttpHeaders(), null);

		assertThat(report.isValid()).isTrue();
	}

	private ValidationReport validate(String path, HttpMethod method, int status, HttpHeaders headers, String body) {
		Resolution.Found found = found(path, method);
		return this.validator.validate(this.contract, method.name() + " " + found.template(), found, status, headers,
				body.getBytes(StandardCharsets.UTF_8));
	}

	private static HttpHeaders jsonHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	private Resolution.Found found(String path, HttpMethod method) {
		return (Resolution.Found) this.contract.resolve(path, method);
	}

}
