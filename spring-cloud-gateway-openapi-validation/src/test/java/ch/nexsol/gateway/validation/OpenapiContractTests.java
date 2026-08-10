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

import ch.nexsol.gateway.validation.OpenapiContract.Resolution;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;

/**
 * Verifies how a contract resolves a request to one of its operations: which template
 * wins, what a wrong method reports, and which parameters an operation ends up with.
 */
class OpenapiContractTests {

	private final OpenapiContract contract = Contracts.bookstore();

	@Test
	void resolvesAnOperationAndCapturesItsPathVariables() {
		Resolution resolution = this.contract.resolve("/books/42", HttpMethod.GET);

		assertThat(resolution).isInstanceOf(Resolution.Found.class);
		Resolution.Found found = (Resolution.Found) resolution;
		assertThat(found.template()).isEqualTo("/books/{id}");
		assertThat(found.pathVariables()).containsExactly(entry("id", "42"));
		assertThat(found.operation().getOperationId()).isEqualTo("getBook");
	}

	@Test
	void prefersAConcretePathOverATemplatedOne() {
		Resolution resolution = this.contract.resolve("/books/mine", HttpMethod.GET);

		assertThat(resolution).isInstanceOf(Resolution.Found.class);
		assertThat(((Resolution.Found) resolution).operation().getOperationId()).isEqualTo("listMyBooks");
	}

	@Test
	void mergesThePathItemParametersWithTheOperationOnes() {
		Resolution.Found found = (Resolution.Found) this.contract.resolve("/books/7", HttpMethod.GET);

		assertThat(found.parameters()).extracting("name").containsExactlyInAnyOrder("id", "verbose");
	}

	@Test
	void reportsTheDeclaredMethodsWhenTheMethodIsNotOneOfThem() {
		Resolution resolution = this.contract.resolve("/books/42", HttpMethod.DELETE);

		assertThat(resolution).isInstanceOf(Resolution.MethodNotAllowed.class);
		Resolution.MethodNotAllowed notAllowed = (Resolution.MethodNotAllowed) resolution;
		assertThat(notAllowed.template()).isEqualTo("/books/{id}");
		assertThat(notAllowed.allowedMethods()).containsExactly("GET");
	}

	@Test
	void reportsAPathTheContractDoesNotDeclare() {
		assertThat(this.contract.resolve("/authors", HttpMethod.GET)).isInstanceOf(Resolution.PathNotFound.class);
	}

	@Test
	void doesNotLetAPathVariableSwallowASegment() {
		// /books/{id} must not match /books/1/reviews, which the contract does not
		// declare.
		assertThat(this.contract.resolve("/books/1/reviews", HttpMethod.GET))
			.isInstanceOf(Resolution.PathNotFound.class);
	}

	@Test
	void compilesTheSameSchemaOnlyOnce() {
		var schema = ((Resolution.Found) this.contract.resolve("/books", HttpMethod.POST)).operation()
			.getRequestBody()
			.getContent()
			.get("application/json")
			.getSchema();

		assertThat(this.contract.compiledSchema("POST /books#request", schema))
			.isSameAs(this.contract.compiledSchema("POST /books#request", schema));
	}

	@Test
	void refusesAContractThatCannotBeRead() {
		assertThatExceptionOfType(OpenapiContractException.class)
			.isThrownBy(() -> Contracts.load("classpath:openapi/does-not-exist.yaml"))
			.withMessageContaining("does not exist");
	}

}
