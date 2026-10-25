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

package ch.nexsol.gateway.mcp.hub;

import java.net.URI;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.swagger.v3.oas.models.PathItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperationToolsTests {

	private static final URI BASE = URI.create("http://gateway:8181/petstore");

	@Test
	void namesEveryToolAfterTheServiceAndTheOperation() {
		List<ApiTool> tools = OperationTools.of("petstore", Contracts.petstore(), BASE, true);
		assertThat(tools).extracting((tool) -> tool.tool().name())
			.containsExactly("petstore__list_pets", "petstore__create_pet", "petstore__get_pets_by",
					"petstore__delete_pet");
	}

	@Test
	void leavesOutTheOperationsThatChangeStateUnlessWritingIsEnabled() {
		List<ApiTool> tools = OperationTools.of("petstore", Contracts.petstore(), BASE, false);
		assertThat(tools).extracting((tool) -> tool.tool().name())
			.containsExactly("petstore__list_pets", "petstore__get_pets_by");
	}

	@Test
	@SuppressWarnings("unchecked")
	void buildsTheInputSchemaFromTheParametersKeepingOnlyJsonSchemaKeywords() {
		ApiTool listPets = OperationTools.of("petstore", Contracts.petstore(), BASE, false).get(0);
		Map<String, Object> schema = listPets.tool().inputSchema();
		assertThat(schema).containsEntry("type", "object").containsEntry("required", List.of("X-Tenant"));
		Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
		assertThat(properties).containsOnlyKeys("limit", "X-Tenant");
		assertThat((Map<String, Object>) properties.get("limit")).containsEntry("type", "integer")
			.containsEntry("minimum", 1L)
			.doesNotContainKey("exclusiveMinimum");
		assertThat(listPets.operation().parameters()).containsEntry("limit", ApiOperation.Location.QUERY)
			.containsEntry("X-Tenant", ApiOperation.Location.HEADER);
		assertThat(listPets.operation().urlTemplate()).isEqualTo("http://gateway:8181/petstore/pets");
		assertThat(listPets.operation().bodyArgument()).isNull();
	}

	@Test
	@SuppressWarnings("unchecked")
	void carriesTheRequestBodyAsAnArgumentWithItsResolvedSchema() {
		ApiTool createPet = OperationTools.of("petstore", Contracts.petstore(), BASE, true).get(1);
		Map<String, Object> schema = createPet.tool().inputSchema();
		assertThat(schema).containsEntry("required", List.of("body"));
		Map<String, Object> body = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("body");
		assertThat(body).containsEntry("type", "object").containsEntry("required", List.of("name"));
		Map<String, Object> tag = (Map<String, Object>) ((Map<String, Object>) body.get("properties")).get("tag");
		// nullable is an OpenAPI 3.0 keyword; JSON Schema says it as a second type. The
		// example is an annotation the client does not need.
		assertThat(tag).containsEntry("type", List.of("string", "null")).doesNotContainKey("example");
		assertThat(createPet.operation().bodyArgument()).isEqualTo("body");
		assertThat(createPet.operation().method()).isEqualTo(PathItem.HttpMethod.POST);
	}

	@Test
	void describesWhatEachToolDoesAndHowSafeItIs() {
		List<ApiTool> tools = OperationTools.of("petstore", Contracts.petstore(), BASE, true);
		Tool listPets = tools.get(0).tool();
		assertThat(listPets.title()).isEqualTo("List the pets");
		assertThat(listPets.description()).contains("Every pet of the store").contains("[GET /pets of petstore]");
		assertThat(listPets.annotations().readOnlyHint()).isTrue();
		assertThat(listPets.annotations().destructiveHint()).isFalse();
		Tool deletePet = tools.get(3).tool();
		assertThat(deletePet.annotations().readOnlyHint()).isFalse();
		assertThat(deletePet.annotations().destructiveHint()).isTrue();
		assertThat(deletePet.annotations().idempotentHint()).isTrue();
	}

	@Test
	void keepsToolNamesWithinTheLimitAndDistinct() {
		String service = "a-service-with-a-very-long-name-indeed-longer-than-most";
		assertThat(OperationTools.sanitize("SERVICE-A")).isEqualTo("service-a");
		assertThat(OperationTools.snake("getUserById")).isEqualTo("get_user_by_id");
		List<ApiTool> tools = OperationTools.of(service, Contracts.petstore(), BASE, true);
		assertThat(tools).allSatisfy(
				(tool) -> assertThat(tool.tool().name()).hasSizeLessThanOrEqualTo(OperationTools.MAX_NAME_LENGTH));
		assertThat(tools).extracting((tool) -> tool.tool().name()).doesNotHaveDuplicates();
	}

}
