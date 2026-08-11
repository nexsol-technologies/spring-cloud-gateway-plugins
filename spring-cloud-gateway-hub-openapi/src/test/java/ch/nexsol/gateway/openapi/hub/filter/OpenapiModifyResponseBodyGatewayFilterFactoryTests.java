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

package ch.nexsol.gateway.openapi.hub.filter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenapiModifyResponseBodyGatewayFilterFactory#rewrite}.
 */
class OpenapiModifyResponseBodyGatewayFilterFactoryTests {

	private static final URI GATEWAY_URI = URI.create("https://gateway.example.ch");

	private static final String WELL_KNOWN = "/.well-known/openid-configuration";

	private final ObjectMapper jsonMapper = JsonMapper.builder().build();

	private final ObjectMapper yamlMapper = YAMLMapper.builder().build();

	private final OpenapiModifyResponseBodyGatewayFilterFactory factory = new OpenapiModifyResponseBodyGatewayFilterFactory(
			List.of(), Set.of(), Set.of(), GATEWAY_URI);

	@Test
	void rewritesServersOfAJsonDocumentWithTheGatewayUrl() {
		byte[] body = """
				{"openapi":"3.0.1","servers":[{"url":"http://service-a:8080"}],"paths":{}}
				""".getBytes(StandardCharsets.UTF_8);

		byte[] result = this.factory.rewrite(body, "/service-a");

		assertThat(serverUrls(this.jsonMapper, result)).containsExactly("https://gateway.example.ch/service-a");
	}

	@Test
	void rewritesServersOfAYamlDocumentAndKeepsItAsYaml() {
		byte[] body = ("openapi: 3.0.1\n" + "servers:\n" + "  - url: http://service-a:8080\n")
			.getBytes(StandardCharsets.UTF_8);

		byte[] result = this.factory.rewrite(body, "/service-a");

		assertThat(serverUrls(this.yamlMapper, result)).containsExactly("https://gateway.example.ch/service-a");
	}

	@Test
	void forwardsADocumentThatIsNeitherJsonNorYamlUnchanged() {
		byte[] body = "this is not a structured document".getBytes(StandardCharsets.UTF_8);

		byte[] result = this.factory.rewrite(body, "/service-a");

		assertThat(result).isEqualTo(body);
	}

	@Test
	void returnsAnEmptyBodyUnchanged() {
		byte[] empty = new byte[0];

		assertThat(this.factory.rewrite(empty, "/service-a")).isEqualTo(empty);
		assertThat(this.factory.rewrite(null, "/service-a")).isNull();
	}

	@Test
	void pointsTheOpenIdConnectSchemeAtTheGatewayIssuerRatherThanTheInternalOneOfTheService() {
		byte[] result = withIssuers(Map.of("local", "https://auth.example.ch" + WELL_KNOWN)).rewrite(secured(),
				"/service-a");

		assertThat(securitySchemes(result).keySet()).containsExactly("bearer-oidc");
		assertThat(scheme(result, "bearer-oidc").get("openIdConnectUrl"))
			.isEqualTo("https://auth.example.ch" + WELL_KNOWN);
	}

	@Test
	void leavesTheDocumentAloneWhenNoIssuerIsAdvertised() {
		byte[] result = this.factory.rewrite(secured(), "/service-a");

		assertThat(scheme(result, "bearer-oidc").get("openIdConnectUrl"))
			.isEqualTo("https://internal.example" + WELL_KNOWN);
	}

	@Test
	void leavesASchemeThatIsNotOpenIdConnectAlone() {
		byte[] body = """
				{"openapi":"3.0.1","components":{"securitySchemes":{
				"basic":{"type":"http","scheme":"basic"}}}}
				""".getBytes(StandardCharsets.UTF_8);

		byte[] result = withIssuers(Map.of("local", "https://auth.example.ch" + WELL_KNOWN)).rewrite(body,
				"/service-a");

		assertThat(scheme(result, "basic").keySet()).doesNotContain("openIdConnectUrl");
	}

	/**
	 * A gateway validating several tenants has several issuers to offer, and an OpenAPI
	 * {@code security} list is a disjunction: one scheme per tenant, named after it, and
	 * one alternative per tenant wherever the original was required. That is what makes
	 * the console offer the tenants as a choice rather than one of them as a fact.
	 */
	@Test
	void offersOneSchemeAndOneAlternativePerTenantWhenTheGatewayValidatesSeveral() {
		Map<String, String> tenants = new LinkedHashMap<>();
		tenants.put("local", "https://local.example.ch" + WELL_KNOWN);
		tenants.put("partner", "https://partner.example.ch" + WELL_KNOWN);

		byte[] result = withIssuers(tenants).rewrite(secured(), "/service-a");

		assertThat(securitySchemes(result).keySet()).containsExactly("bearer-oidc-local", "bearer-oidc-partner");
		assertThat(scheme(result, "bearer-oidc-local").get("openIdConnectUrl"))
			.isEqualTo("https://local.example.ch" + WELL_KNOWN);
		assertThat(scheme(result, "bearer-oidc-partner").get("openIdConnectUrl"))
			.isEqualTo("https://partner.example.ch" + WELL_KNOWN);
		assertThat(requiredSchemes(result, "security")).containsExactly("bearer-oidc-local", "bearer-oidc-partner");
		// The operations carrying their own requirement are rewritten too: a
		// per-operation
		// 'security' overrides the root one, so leaving them would offer the tenants
		// everywhere but where it matters.
		assertThat(operationRequiredSchemes(result)).containsExactly("bearer-oidc-local", "bearer-oidc-partner");
	}

	private OpenapiModifyResponseBodyGatewayFilterFactory withIssuers(Map<String, String> issuers) {
		return new OpenapiModifyResponseBodyGatewayFilterFactory(List.of(), Set.of(), Set.of(), GATEWAY_URI, issuers);
	}

	private static byte[] secured() {
		return """
				{"openapi":"3.0.1",
				"security":[{"bearer-oidc":[]}],
				"paths":{"/alerts":{"get":{"security":[{"bearer-oidc":["read"]}]}}},
				"components":{"securitySchemes":{"bearer-oidc":{
				"type":"openIdConnect",
				"openIdConnectUrl":"https://internal.example/.well-known/openid-configuration"}}}}
				""".getBytes(StandardCharsets.UTF_8);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> securitySchemes(byte[] document) {
		Map<String, Object> parsed = this.jsonMapper.readValue(document, Map.class);
		Map<String, Object> components = (Map<String, Object>) parsed.get("components");
		return (Map<String, Object>) components.get("securitySchemes");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> scheme(byte[] document, String name) {
		return (Map<String, Object>) securitySchemes(document).get(name);
	}

	private List<String> requiredSchemes(byte[] document, String key) {
		Map<?, ?> parsed = this.jsonMapper.readValue(document, Map.class);
		return names((List<?>) parsed.get(key));
	}

	private List<String> operationRequiredSchemes(byte[] document) {
		Map<?, ?> parsed = this.jsonMapper.readValue(document, Map.class);
		Map<?, ?> alerts = (Map<?, ?>) ((Map<?, ?>) parsed.get("paths")).get("/alerts");
		return names((List<?>) ((Map<?, ?>) alerts.get("get")).get("security"));
	}

	private static List<String> names(List<?> requirements) {
		return requirements.stream()
			.flatMap((requirement) -> ((Map<?, ?>) requirement).keySet().stream())
			.map(String.class::cast)
			.toList();
	}

	private static List<String> serverUrls(ObjectMapper mapper, byte[] document) {
		Map<?, ?> parsed = mapper.readValue(document, Map.class);
		List<?> servers = (List<?>) parsed.get("servers");
		return servers.stream().map((server) -> (String) ((Map<?, ?>) server).get("url")).toList();
	}

}
