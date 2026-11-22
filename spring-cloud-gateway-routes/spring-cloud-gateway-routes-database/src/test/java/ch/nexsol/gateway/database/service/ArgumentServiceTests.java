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

package ch.nexsol.gateway.database.service;

import java.util.Map;

import org.apache.commons.text.StringEscapeUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ArgumentService}: what it writes has to be what it reads back,
 * whatever characters an argument holds.
 */
class ArgumentServiceTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final ArgumentService argumentService = new ArgumentService(this.objectMapper);

	@Test
	void shouldReadBackAnArgumentHoldingABackslash() {
		Map<String, String> args = Map.of("header", "X-Id", "regexp", "\\d+");

		String stored = this.argumentService.mapArgumentsToJsonString(args);

		assertThat(this.argumentService.jsonStringArgumentsToMap(stored)).isEqualTo(args);
	}

	@Test
	void shouldReadBackAnArgumentHoldingADoubleQuote() {
		Map<String, String> args = Map.of("value", "say \"hello\"");

		String stored = this.argumentService.mapArgumentsToJsonString(args);

		assertThat(this.argumentService.jsonStringArgumentsToMap(stored)).isEqualTo(args);
	}

	@Test
	void shouldReadBackPlainArguments() {
		Map<String, String> args = Map.of("name", "X-Request-red", "value", "blue");

		String stored = this.argumentService.mapArgumentsToJsonString(args);

		assertThat(this.argumentService.jsonStringArgumentsToMap(stored)).isEqualTo(args);
	}

	@Test
	void shouldStillReadARowWrittenEscaped() {
		// What the previous versions stored: the JSON document, escaped a second time.
		Map<String, String> args = Map.of("header", "X-Id", "regexp", "\\d+");
		String legacy = StringEscapeUtils.escapeJson(this.objectMapper.writeValueAsString(args));

		assertThat(legacy).startsWith("{\\\"");
		assertThat(this.argumentService.jsonStringArgumentsToMap(legacy)).isEqualTo(args);
	}

	@Test
	void shouldRejectWhatIsNotJson() {
		assertThatThrownBy(() -> this.argumentService.jsonStringArgumentsToMap("not json"))
			.isInstanceOf(JacksonException.class);
	}

}
