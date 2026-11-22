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

import ch.nexsol.gateway.validation.OpenapiContract.Resolution;
import com.networknt.schema.Schema;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what a compiled schema holds. The schemas of a contract refer to its
 * {@code components}; carrying a copy of that section inside each compiled schema makes a
 * large contract cost its components once per parameter, per body and per response,
 * instead of once.
 */
class CompiledSchemaFootprintTests {

	private final OpenapiContract contract = Contracts.load("classpath:openapi/limits.yaml");

	private final OpenapiRequestValidator validator = new OpenapiRequestValidator();

	@Test
	void aSchemaReferringToTheComponentsDoesNotCarryACopyOfThem() {
		assertThat(body("/catalog", "[{\"name\":\"pen\"}]").isValid()).isTrue();

		assertThat(compiled("POST /catalog#request").getSchemaNode().has("components")).isFalse();
	}

	@Test
	void aParameterSchemaDoesNotCarryTheComponents() {
		Resolution.Found found = (Resolution.Found) this.contract.resolve("/catalog", HttpMethod.GET);
		ValidationReport report = this.validator.validateParameters(this.contract, "GET /catalog", found,
				MockServerHttpRequest.get("/catalog?page=2").build());

		assertThat(report.isValid()).isTrue();
		assertThat(compiled("GET /catalog#param:query:page").getSchemaNode().has("components")).isFalse();
	}

	@Test
	void stillFollowsAReferenceToTheComponents() {
		assertThat(body("/catalog", "[{\"name\":\"pen\",\"price\":1.25}]").isValid()).isTrue();

		ValidationReport broken = body("/catalog", "[{\"price\":1.25}]");
		assertThat(broken.isValid()).isFalse();
		assertThat(broken.describe()).contains("name");
	}

	@Test
	void stillFollowsASchemaReferringToItself() {
		String nested = "{\"name\":\"a\",\"children\":[{\"name\":\"b\",\"children\":[{\"name\":\"c\"}]}]}";
		assertThat(body("/categories", nested).isValid()).isTrue();

		String brokenDeepDown = "{\"name\":\"a\",\"children\":[{\"name\":\"b\",\"children\":[{\"label\":\"c\"}]}]}";
		ValidationReport broken = body("/categories", brokenDeepDown);
		assertThat(broken.isValid()).isFalse();
		assertThat(broken.describe()).contains("/children/0/children/0");
	}

	@Test
	void stillFollowsTheReferencesOfA31Contract() {
		OpenapiContract notes = Contracts.load("classpath:openapi/notes-31.yaml");
		Resolution.Found found = (Resolution.Found) notes.resolve("/notes", HttpMethod.POST);

		assertThat(note(notes, found, "{\"text\":\"hello\",\"label\":{\"name\":\"urgent\"}}").isValid()).isTrue();
		assertThat(note(notes, found, "{\"text\":\"hello\",\"label\":null}").isValid()).isTrue();
		assertThat(note(notes, found, "{\"text\":\"hello\",\"label\":{\"colour\":\"red\"}}").isValid()).isFalse();
	}

	@Test
	void followsTheReferenceOfAPropertyNamedLikeAKeyword() {
		OpenapiContract notes = Contracts.load("classpath:openapi/notes-31.yaml");
		Resolution.Found found = (Resolution.Found) notes.resolve("/notes", HttpMethod.POST);

		assertThat(note(notes, found, "{\"text\":\"hello\",\"default\":{\"name\":\"urgent\"}}").isValid()).isTrue();
		assertThat(note(notes, found, "{\"text\":\"hello\",\"default\":{\"colour\":\"red\"}}").isValid()).isFalse();
	}

	private ValidationReport note(OpenapiContract notes, Resolution.Found found, String json) {
		return this.validator.validateBody(notes, "POST /notes", found, MediaType.APPLICATION_JSON,
				json.getBytes(StandardCharsets.UTF_8));
	}

	private ValidationReport body(String path, String json) {
		Resolution.Found found = (Resolution.Found) this.contract.resolve(path, HttpMethod.POST);
		return this.validator.validateBody(this.contract, "POST " + path, found, MediaType.APPLICATION_JSON,
				json.getBytes(StandardCharsets.UTF_8));
	}

	private Schema compiled(String cacheKey) {
		return this.contract.schemas().compiled(cacheKey, () -> {
			throw new AssertionError("the schema under '" + cacheKey + "' was not compiled by the validation");
		});
	}

}
