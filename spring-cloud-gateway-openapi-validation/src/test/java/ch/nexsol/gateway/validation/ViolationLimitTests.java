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
import java.util.Collections;

import ch.nexsol.gateway.validation.OpenapiContract.Resolution;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the violations of one message are listed up to a limit and no further. A
 * body breaking its schema on every element of a large array would otherwise turn into as
 * many violations as it has elements, each of them copied into the answer, the log line
 * and the audit event.
 */
class ViolationLimitTests {

	private static final int LIMIT = 20;

	private final OpenapiContract contract = Contracts.load("classpath:openapi/limits.yaml");

	@Test
	void listsNoMoreThanTheLimitForARequestBodyBrokenOnEveryElement() {
		ValidationReport report = requestBody(arrayOfZeros(5000));

		assertThat(report.isValid()).isFalse();
		assertThat(report.errors()).hasSize(LIMIT + 1);
		assertThat(report.errors().get(LIMIT)).contains("only the first " + LIMIT);
	}

	@Test
	void listsNoMoreThanTheLimitForAResponseBodyBrokenOnEveryElement() {
		ValidationReport report = responseBody(arrayOfZeros(5000));

		assertThat(report.isValid()).isFalse();
		assertThat(report.errors()).hasSize(LIMIT + 1);
	}

	@Test
	void theDescriptionOfSuchABodyStaysShort() {
		assertThat(requestBody(arrayOfZeros(100_000)).describe()).hasSizeLessThan(4096);
	}

	@Test
	void listsEveryViolationOfABodyUnderTheLimit() {
		ValidationReport report = requestBody(arrayOfZeros(3));

		assertThat(report.errors()).hasSize(3).noneMatch((error) -> error.contains("only the first"));
	}

	@Test
	void listsInFullABodyBreakingTheContractExactlyAsOftenAsTheLimit() {
		ValidationReport report = requestBody(arrayOfZeros(LIMIT));

		assertThat(report.errors()).hasSize(LIMIT).noneMatch((error) -> error.contains("only the first"));
	}

	@Test
	void stillRejectsABodyWhoseOnlyViolationComesAfterManyValidElements() {
		String valid = String.join(",", Collections.nCopies(2000, "{\"name\":\"pen\"}"));

		ValidationReport report = requestBody("[" + valid + ",0]");

		assertThat(report.isValid()).isFalse();
		assertThat(report.errors()).hasSize(1);
	}

	@Test
	void stillAcceptsALargeBodyThatHonoursTheContract() {
		String valid = String.join(",", Collections.nCopies(2000, "{\"name\":\"pen\",\"price\":1.25}"));

		assertThat(requestBody("[" + valid + "]").isValid()).isTrue();
	}

	private ValidationReport requestBody(String body) {
		Resolution.Found found = (Resolution.Found) this.contract.resolve("/catalog", HttpMethod.POST);
		return new OpenapiRequestValidator().validateBody(this.contract, "POST /catalog", found,
				MediaType.APPLICATION_JSON, body.getBytes(StandardCharsets.UTF_8));
	}

	private ValidationReport responseBody(String body) {
		Resolution.Found found = (Resolution.Found) this.contract.resolve("/catalog", HttpMethod.GET);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return new OpenapiResponseValidator().validate(this.contract, "GET /catalog", found, 200, headers,
				body.getBytes(StandardCharsets.UTF_8));
	}

	private static String arrayOfZeros(int count) {
		return "[" + String.join(",", Collections.nCopies(count, "0")) + "]";
	}

}
