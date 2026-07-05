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
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenapiModifyResponseBodyGatewayFilterFactory#rewriteServers}.
 */
class OpenapiModifyResponseBodyGatewayFilterFactoryTests {

	private static final URI GATEWAY_URI = URI.create("https://gateway.example.ch");

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final OpenapiModifyResponseBodyGatewayFilterFactory factory = new OpenapiModifyResponseBodyGatewayFilterFactory(
			List.of(), Set.of(), Set.of(), GATEWAY_URI);

	@Test
	void rewritesServersOfAJsonDocumentWithTheGatewayUrl() throws Exception {
		byte[] body = """
				{"openapi":"3.0.1","servers":[{"url":"http://service-a:8080"}],"paths":{}}
				""".getBytes(StandardCharsets.UTF_8);

		byte[] result = this.factory.rewriteServers(body, "/service-a");

		Map<?, ?> document = this.objectMapper.readValue(result, Map.class);
		List<?> servers = (List<?>) document.get("servers");
		assertThat(servers).hasSize(1);
		assertThat(((Map<?, ?>) servers.get(0)).get("url")).isEqualTo("https://gateway.example.ch/service-a");
	}

	@Test
	void forwardsANonJsonDocumentUnchangedInsteadOfDroppingIt() {
		byte[] body = ("openapi: 3.0.1\n" + "servers:\n" + "  - url: http://service-a:8080\n")
			.getBytes(StandardCharsets.UTF_8);

		byte[] result = this.factory.rewriteServers(body, "/service-a");

		assertThat(result).isEqualTo(body);
	}

	@Test
	void returnsAnEmptyBodyUnchanged() {
		byte[] empty = new byte[0];

		assertThat(this.factory.rewriteServers(empty, "/service-a")).isEqualTo(empty);
		assertThat(this.factory.rewriteServers(null, "/service-a")).isNull();
	}

}
