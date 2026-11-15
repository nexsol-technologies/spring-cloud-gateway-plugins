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

import java.math.BigInteger;
import java.net.URI;
import java.util.Map;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a contract may and may not put in the tools read from it.
 */
class OperationToolsContractTests {

	private static final URI BASE = URI.create("http://gateway:8181/things");

	@Test
	@SuppressWarnings("unchecked")
	void leavesOutTheHeadersAContractHasNoSayOver() {
		// OpenAPI has the definition of these three ignored. As an argument,
		// Authorization would also join the caller's own in the request.
		OpenAPI contract = parse("""
				{"name":"Authorization","in":"header","required":true,"schema":{"type":"string"}},
				{"name":"accept","in":"header","schema":{"type":"string"}},
				{"name":"Content-Type","in":"header","schema":{"type":"string"}},
				{"name":"X-Trace","in":"header","schema":{"type":"string"}}""");
		ApiTool tool = OperationTools.of("svc", contract, BASE, false, true).get(0);
		assertThat((Map<String, Object>) tool.tool().inputSchema().get("properties")).containsOnlyKeys("X-Trace");
		assertThat(tool.tool().inputSchema()).doesNotContainKey("required");
		assertThat(tool.operation().parameters()).containsOnlyKeys("X-Trace");
	}

	@Test
	@SuppressWarnings("unchecked")
	void writesABoundBeyondALongAsTheNumberItIs() {
		OpenAPI contract = parse("""
				{"name":"big","in":"query","schema":{"type":"integer","maximum":1000000000000000000000000}},
				{"name":"small","in":"query","schema":{"type":"integer","maximum":10}},
				{"name":"ratio","in":"query","schema":{"type":"number","maximum":0.5}}""");
		Map<String, Object> properties = (Map<String, Object>) OperationTools.of("svc", contract, BASE, false, true)
			.get(0)
			.tool()
			.inputSchema()
			.get("properties");
		assertThat((Map<String, Object>) properties.get("big")).containsEntry("maximum",
				new BigInteger("1000000000000000000000000"));
		assertThat((Map<String, Object>) properties.get("small")).containsEntry("maximum", 10L);
		assertThat((Map<String, Object>) properties.get("ratio")).containsEntry("maximum", 0.5d);
	}

	private static OpenAPI parse(String parameters) {
		String contract = """
				{"openapi":"3.0.3","info":{"title":"T","version":"1"},"paths":{"/things":{"get":{
				"operationId":"listThings","parameters":[%s],
				"responses":{"200":{"description":"ok"}}}}}}""".formatted(parameters);
		return new OpenAPIV3Parser().readContents(contract, null, null).getOpenAPI();
	}

}
