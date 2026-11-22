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

import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.validation.OpenapiContract.Resolution;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies how a request is resolved to an operation: what is worked out once when the
 * contract is read rather than for every request, and that the operation found is the one
 * trying every path in turn would find.
 */
class OpenapiContractResolutionTests {

	@Test
	void readsTheOperationsOfAPathOnceRatherThanForEveryRequest() {
		CountingPathItem books = new CountingPathItem();
		books.setGet(new Operation().operationId("getBook"));
		OpenapiContract contract = contract(Map.of("/books/{id}", books));
		int readWhenTheContractWas = books.reads;

		for (int request = 0; request < 10; request++) {
			assertThat(contract.resolve("/books/" + request, HttpMethod.GET)).isInstanceOf(Resolution.Found.class);
			assertThat(contract.resolve("/books/" + request, HttpMethod.DELETE))
				.isInstanceOf(Resolution.MethodNotAllowed.class);
		}

		assertThat(books.reads).isEqualTo(readWhenTheContractWas);
	}

	@Test
	void aConcretePathWinsOverATemplateEvenForAMethodItDoesNotDeclare() {
		Paths paths = new Paths();
		paths.addPathItem("/books/{id}", new PathItem().delete(new Operation().operationId("deleteBook")));
		paths.addPathItem("/books/mine", new PathItem().get(new Operation().operationId("listMine")));
		OpenapiContract contract = new OpenapiContract(new OpenAPI().paths(paths));

		assertThat(found(contract, "/books/mine", HttpMethod.GET).template()).isEqualTo("/books/mine");
		assertThat(contract.resolve("/books/mine", HttpMethod.DELETE))
			.isInstanceOfSatisfying(Resolution.MethodNotAllowed.class, (notAllowed) -> {
				assertThat(notAllowed.template()).isEqualTo("/books/mine");
				assertThat(notAllowed.allowedMethods()).containsExactly("GET");
			});
		assertThat(found(contract, "/books/7", HttpMethod.DELETE).pathVariables()).containsEntry("id", "7");
	}

	@Test
	void aTemplateWithFewerVariablesWinsOverOneWithMore() {
		Paths paths = new Paths();
		paths.addPathItem("/shelves/{shelf}/{book}", new PathItem().get(new Operation().operationId("getBook")));
		paths.addPathItem("/shelves/{shelf}/count", new PathItem().get(new Operation().operationId("count")));
		OpenapiContract contract = new OpenapiContract(new OpenAPI().paths(paths));

		assertThat(found(contract, "/shelves/a/count", HttpMethod.GET).template()).isEqualTo("/shelves/{shelf}/count");
		assertThat(found(contract, "/shelves/a/dune", HttpMethod.GET).pathVariables()).containsEntry("shelf", "a")
			.containsEntry("book", "dune");
	}

	@Test
	void theOrderOfTheContractDecidesBetweenTemplatesOfTheSameShape() {
		Paths paths = new Paths();
		paths.addPathItem("/x/{a}", new PathItem().get(new Operation().operationId("first")));
		paths.addPathItem("/{b}/y", new PathItem().get(new Operation().operationId("second")));
		OpenapiContract contract = new OpenapiContract(new OpenAPI().paths(paths));

		assertThat(found(contract, "/x/y", HttpMethod.GET).template()).isEqualTo("/x/{a}");
		assertThat(found(contract, "/z/y", HttpMethod.GET).template()).isEqualTo("/{b}/y");
	}

	@Test
	void aVariableStaysWithinItsSegment() {
		Paths paths = new Paths();
		paths.addPathItem("/files/{name}.json", new PathItem().get(new Operation().operationId("getFile")));
		paths.addPathItem("/books", new PathItem().get(new Operation().operationId("listBooks")));
		OpenapiContract contract = new OpenapiContract(new OpenAPI().paths(paths));

		assertThat(found(contract, "/files/a.json", HttpMethod.GET).pathVariables()).containsEntry("name", "a");
		assertThat(contract.resolve("/files/a/b.json", HttpMethod.GET)).isInstanceOf(Resolution.PathNotFound.class);
		assertThat(contract.resolve("/files/.json", HttpMethod.GET)).isInstanceOf(Resolution.PathNotFound.class);
		assertThat(contract.resolve("/books/", HttpMethod.GET)).isInstanceOf(Resolution.PathNotFound.class);
		assertThat(contract.resolve("/book", HttpMethod.GET)).isInstanceOf(Resolution.PathNotFound.class);
	}

	@Test
	void theParametersOfTheOperationOverrideThoseOfThePath() {
		Parameter fromThePath = new Parameter().name("page").in("query").required(false);
		Parameter fromTheOperation = new Parameter().name("page").in("query").required(true);
		Parameter onlyOnThePath = new Parameter().name("id").in("path").required(true);
		PathItem item = new PathItem().parameters(List.of(fromThePath, onlyOnThePath))
			.get(new Operation().parameters(List.of(fromTheOperation)))
			.put(new Operation());
		OpenapiContract contract = contract(Map.of("/books/{id}", item));

		assertThat(found(contract, "/books/7", HttpMethod.GET).parameters()).containsExactly(fromTheOperation,
				onlyOnThePath);
		assertThat(found(contract, "/books/7", HttpMethod.PUT).parameters()).containsExactly(fromThePath,
				onlyOnThePath);
	}

	private static OpenapiContract contract(Map<String, PathItem> items) {
		Paths paths = new Paths();
		items.forEach(paths::addPathItem);
		return new OpenapiContract(new OpenAPI().paths(paths));
	}

	private static Resolution.Found found(OpenapiContract contract, String path, HttpMethod method) {
		return (Resolution.Found) contract.resolve(path, method);
	}

	static class CountingPathItem extends PathItem {

		int reads;

		@Override
		public Map<HttpMethod, Operation> readOperationsMap() {
			this.reads++;
			return super.readOperationsMap();
		}

	}

}
