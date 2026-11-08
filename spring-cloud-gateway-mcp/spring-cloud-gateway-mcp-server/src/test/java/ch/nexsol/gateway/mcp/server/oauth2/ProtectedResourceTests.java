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

package ch.nexsol.gateway.mcp.server.oauth2;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProtectedResourceTests {

	private static final URI REQUEST = URI.create("https://gateway.example.com:8443/mcp");

	private final ProtectedResource derived = new ProtectedResource(null, "/mcp", List.of("https://idp/realms/x"),
			List.of());

	@Test
	void derivesTheResourceAndTheMetadataUrlFromTheRequest() {
		assertThat(this.derived.resource(REQUEST)).isEqualTo("https://gateway.example.com:8443/mcp");
		assertThat(this.derived.metadataUrl(REQUEST))
			.isEqualTo("https://gateway.example.com:8443/.well-known/oauth-protected-resource/mcp");
		assertThat(this.derived.metadataPaths()).containsExactly("/.well-known/oauth-protected-resource",
				"/.well-known/oauth-protected-resource/mcp");
	}

	@Test
	void publishesTheResourceTheApplicationNamedWhateverTheRequestSays() {
		ProtectedResource named = new ProtectedResource("https://api.example.com/agent", "/mcp",
				List.of("https://idp/realms/x"), List.of("gateway.read"));
		URI request = URI.create("http://10.0.0.7:8080/mcp");
		assertThat(named.resource(request)).isEqualTo("https://api.example.com/agent");
		assertThat(named.metadataUrl(request))
			.isEqualTo("https://api.example.com/.well-known/oauth-protected-resource/agent");
		assertThat(named.metadataPaths()).containsExactly("/.well-known/oauth-protected-resource",
				"/.well-known/oauth-protected-resource/agent");
	}

	@Test
	void writesTheDocumentOfRfc9728() {
		ProtectedResource scoped = new ProtectedResource(null, "/mcp", List.of("https://idp/realms/x"),
				List.of("gateway.read", "gateway.write"));
		assertThat(scoped.metadata(REQUEST)).containsExactly(
				Map.entry("resource", "https://gateway.example.com:8443/mcp"),
				Map.entry("authorization_servers", List.of("https://idp/realms/x")),
				Map.entry("scopes_supported", List.of("gateway.read", "gateway.write")),
				Map.entry("bearer_methods_supported", List.of("header")));
		assertThat(this.derived.metadata(REQUEST)).doesNotContainKey("scopes_supported");
	}

	@Test
	void challengesWithTheMetadataUrlAndKeepsWhatTheChainSaid() {
		String metadata = "resource_metadata=\"https://gateway.example.com:8443/.well-known/oauth-protected-resource/mcp\"";
		assertThat(this.derived.challenge(null, REQUEST)).isEqualTo("Bearer " + metadata);
		assertThat(this.derived.challenge("Bearer", REQUEST)).isEqualTo("Bearer " + metadata);
		assertThat(this.derived.challenge("Bearer error=\"invalid_token\"", REQUEST))
			.isEqualTo("Bearer error=\"invalid_token\", " + metadata);
		assertThat(this.derived.challenge("Basic realm=\"console\"", REQUEST)).isEqualTo("Bearer " + metadata);
		assertThat(this.derived.challenge("Bearer " + metadata, REQUEST)).isEqualTo("Bearer " + metadata);
	}

	@Test
	void requiresAnAuthorizationServer() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ProtectedResource(null, "/mcp", List.of(), List.of()));
	}

}
