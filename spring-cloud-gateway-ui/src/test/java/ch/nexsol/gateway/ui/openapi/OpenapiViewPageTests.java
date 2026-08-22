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

package ch.nexsol.gateway.ui.openapi;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the rendered OpenAPI page carries for the script that configures Scalar.
 * <p>
 * The script reads the switch off an attribute, and an attribute Thymeleaf dropped
 * instead of writing {@code false} leaves the page offering what it was configured not to
 * offer.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.hub-openapi.enabled=true",
		"spring.cloud.gateway.server.webflux.hub-openapi.gateway-uri=http://localhost:8080",
		"spring.cloud.gateway.server.webflux.ui.openapi.try-it=false",
		"spring.cloud.gateway.server.webflux.ui.openapi.extensions.x-roles=Required roles" })
@AutoConfigureWebTestClient(timeout = "300000")
class OpenapiViewPageTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldTellThePageThatTryItIsOff() {
		page().value((body) -> assertThat(body).contains("data-try-it=\"false\""));
	}

	@Test
	void shouldCarryTheExtensionLabelsAsAnAttributeTheScriptCanParse() {
		// The mapping travels as JSON inside an attribute, so its quotes are escaped on
		// the way out and read back unescaped by the dataset accessor.
		page().value((body) -> assertThat(body)
			.contains("data-extension-labels=\"{&quot;x-roles&quot;:&quot;Required roles&quot;}\""));
	}

	@Test
	void shouldStillCarryTheDocumentationUrls() {
		page().value((body) -> assertThat(body).contains("data-config-url=\"/v3/api-docs/swagger-config\"")
			.contains("data-document-url=\"/v3/api-docs\""));
	}

	private WebTestClient.BodySpec<String, ?> page() {
		return this.webTestClient.get().uri("/ui/openapi").exchange().expectStatus().isOk().expectBody(String.class);
	}

}
