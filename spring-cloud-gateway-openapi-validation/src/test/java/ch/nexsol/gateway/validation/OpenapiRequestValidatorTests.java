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

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what the request side of the contract accepts and what it rejects: the
 * parameters first, then the body.
 */
class OpenapiRequestValidatorTests {

	private static final byte[] NO_BODY = {};

	private final OpenapiContract contract = Contracts.bookstore();

	private final OpenapiRequestValidator validator = new OpenapiRequestValidator();

	@Test
	void acceptsParametersThatHonourTheContract() {
		ValidationReport report = validateParameters("/books", HttpMethod.GET,
				MockServerHttpRequest.get("/books?page=2&status=available"));

		assertThat(report.isValid()).isTrue();
	}

	@Test
	void rejectsAQueryParameterThatIsNotTheDeclaredType() {
		ValidationReport report = validateParameters("/books", HttpMethod.GET,
				MockServerHttpRequest.get("/books?page=first"));

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("query parameter 'page'").contains("integer");
	}

	@Test
	void rejectsAQueryParameterOutsideItsDeclaredBounds() {
		ValidationReport report = validateParameters("/books", HttpMethod.GET,
				MockServerHttpRequest.get("/books?page=-1"));

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("query parameter 'page'");
	}

	@Test
	void rejectsAQueryParameterOutsideItsEnumeration() {
		ValidationReport report = validateParameters("/books", HttpMethod.GET,
				MockServerHttpRequest.get("/books?status=lost"));

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("query parameter 'status'");
	}

	@Test
	void acceptsARepeatedParameterAsAnArray() {
		ValidationReport report = validateParameters("/books", HttpMethod.GET,
				MockServerHttpRequest.get("/books?tag=fiction&tag=classic"));

		assertThat(report.isValid()).isTrue();
	}

	@Test
	void rejectsAPathVariableThatIsNotTheDeclaredType() {
		ValidationReport report = validateParameters("/books/abc", HttpMethod.GET,
				MockServerHttpRequest.get("/books/abc"));

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("path parameter 'id'");
	}

	@Test
	void acceptsABodyThatHonoursTheContract() {
		ValidationReport report = validateBody("/books", HttpMethod.POST,
				"{\"title\":\"Dune\",\"author\":\"Herbert\",\"pages\":412}");

		assertThat(report.isValid()).isTrue();
	}

	@Test
	void rejectsABodyMissingARequiredProperty() {
		ValidationReport report = validateBody("/books", HttpMethod.POST, "{\"title\":\"Dune\"}");

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("request body").contains("author");
	}

	@Test
	void rejectsABodyWhoseValueBreaksItsSchema() {
		ValidationReport report = validateBody("/books", HttpMethod.POST,
				"{\"title\":\"Dune\",\"author\":\"Herbert\",\"pages\":0}");

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("request body").contains("pages");
	}

	@Test
	void rejectsABodyThatIsNotValidJson() {
		ValidationReport report = validateBody("/books", HttpMethod.POST, "{\"title\":");

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("not valid JSON");
	}

	@Test
	void rejectsAMissingBodyTheContractRequires() {
		Resolution.Found found = found("/books", HttpMethod.POST);

		ValidationReport report = this.validator.validateBody(this.contract, "POST /books", found, null, NO_BODY);

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("required by the contract but is missing");
	}

	@Test
	void rejectsAContentTypeTheContractDoesNotDeclare() {
		Resolution.Found found = found("/books", HttpMethod.POST);

		ValidationReport report = this.validator.validateBody(this.contract, "POST /books", found, MediaType.TEXT_PLAIN,
				"Dune".getBytes(StandardCharsets.UTF_8));

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("is not declared by the contract");
	}

	@Test
	void acceptsADeclaredUploadWithoutHoldingItAgainstAJsonSchema() {
		Resolution.Found found = found("/books/7/cover", HttpMethod.POST);

		ValidationReport report = this.validator.validateBody(this.contract, "POST /books/{id}/cover", found,
				MediaType.MULTIPART_FORM_DATA, "not json at all".getBytes(StandardCharsets.UTF_8));

		assertThat(report.isValid()).isTrue();
	}

	private ValidationReport validateParameters(String path, HttpMethod method,
			MockServerHttpRequest.BaseBuilder<?> request) {
		Resolution.Found found = found(path, method);
		return this.validator.validateParameters(this.contract, method.name() + " " + found.template(), found,
				request.build());
	}

	private ValidationReport validateBody(String path, HttpMethod method, String body) {
		Resolution.Found found = found(path, method);
		return this.validator.validateBody(this.contract, method.name() + " " + found.template(), found,
				MediaType.APPLICATION_JSON, body.getBytes(StandardCharsets.UTF_8));
	}

	private Resolution.Found found(String path, HttpMethod method) {
		return (Resolution.Found) this.contract.resolve(path, method);
	}

}
