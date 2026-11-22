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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import ch.nexsol.gateway.validation.OpenapiContract.Resolution;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a {@code number} parameter is refused when its magnitude is beyond
 * anything a contract describes. Such a value takes a dozen characters to send, and
 * holding it against a {@code multipleOf} is a division on a number of as many digits as
 * its exponent says: minutes of computation, on the thread serving every other request.
 */
class NumberParameterMagnitudeTests {

	private final OpenapiContract contract = Contracts.load("classpath:openapi/limits.yaml");

	private final OpenapiRequestValidator validator = new OpenapiRequestValidator();

	@Test
	void refusesANumberWhoseExponentIsOutOfProportion() {
		ValidationReport report = validate("/catalog?price=1e2000");

		assertThat(report.isValid()).isFalse();
		assertThat(report.describe()).contains("query parameter 'price'").contains("not a valid number");
	}

	@Test
	void refusesANumberWhoseNegativeExponentIsOutOfProportion() {
		assertThat(validate("/catalog?price=1e-2000").isValid()).isFalse();
	}

	@Test
	void refusesANumberOfTooManyDigits() {
		assertThat(validate("/catalog?price=" + "9".repeat(1500)).isValid()).isFalse();
	}

	@Test
	void refusesSuchANumberAmongTheElementsOfAnArray() {
		assertThat(validate("/catalog?prices=1.25&prices=1e2000").isValid()).isFalse();
	}

	@Test
	void answersAtOnceForAnExponentThatWouldTakeMinutesToDivide() {
		assertThat(CompletableFuture.supplyAsync(() -> validate("/catalog?price=1e999999999")))
			.succeedsWithin(Duration.ofSeconds(5))
			.matches((report) -> !report.isValid());
	}

	@Test
	void stillAcceptsTheNumbersAContractDescribes() {
		assertThat(validate("/catalog?price=12.34").isValid()).isTrue();
		assertThat(validate("/catalog?price=1e3").isValid()).isTrue();
		assertThat(validate("/catalog?price=1.7976931348623157e308").isValid()).isTrue();
		assertThat(validate("/catalog?prices=1.25&prices=2.5").isValid()).isTrue();
	}

	@Test
	void stillHoldsANumberAgainstItsSchema() {
		assertThat(validate("/catalog?price=12.345").isValid()).isFalse();
		assertThat(validate("/catalog?price=twelve").isValid()).isFalse();
	}

	private ValidationReport validate(String uri) {
		Resolution.Found found = (Resolution.Found) this.contract.resolve("/catalog", HttpMethod.GET);
		return this.validator.validateParameters(this.contract, "GET /catalog", found,
				MockServerHttpRequest.get(uri).build());
	}

}
