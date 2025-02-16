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

package ch.nexsol.gateway.filter;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * @author guerricmerle
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext
@ActiveProfiles(profiles = "correlation-id")
public class CorrelationIdFilterIntegrationTests extends BaseWebClientTests {

	@Test
	public void testAddCustomTraceHeader_ShouldAddHeaderWhenTraceIdExists() {
		this.testClient.get()
			.uri("/correlation-id-header")
			.headers((headers) -> headers.set("Host", "www.validatecorrelationidheader.ch"))
			.exchange()
			.expectBody(Map.class)
			.consumeWith((result) -> assertThat(result.getResponseHeaders().containsKey("x-correlation-id"))
				.isEqualTo(true));
	}

}
