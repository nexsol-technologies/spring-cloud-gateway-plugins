/*
 * Copyright 2024 the original author or authors.
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

package ch.nexsol.gateway.filter.factory;

import java.util.Map;

import ch.nexsol.gateway.filter.BaseWebClientTests;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext
@ActiveProfiles(profiles = "convert-http-method")
class ConvertHttpMethodGatewayFilterFactoryTests extends BaseWebClientTests {

	@Test
	void convertHttpMethodGetToPostWorks() {
		this.testClient.get().uri("/foo-post").headers(headers -> {
			headers.set("x-method", HttpMethod.GET.name());
			headers.set("Host", "www.converthttpmethod.ch");
		}).exchange().expectBody(Map.class).consumeWith(result -> {
			assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
			Map<?, ?> response = result.getResponseBody();
			assertThat(response).isNotNull();

			String oldMethod = (String) response.get("old-method");
			assertThat(oldMethod).isNotNull();
			assertThat(oldMethod).isEqualTo(HttpMethod.GET.name());

			String method = (String) response.get("method");
			assertThat(method).isNotNull();
			assertThat(method).isEqualTo(HttpMethod.POST.name());
		});
	}

	@Test
	void convertHttpMethodPostToGettWorks() {
		this.testClient.post().uri("/foo-get").headers(headers -> {
			headers.set("x-method", HttpMethod.POST.name());
			headers.set("Host", "www.converthttpmethod.ch");
		}).exchange().expectBody(Map.class).consumeWith(result -> {
			assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
			Map<?, ?> response = result.getResponseBody();
			assertThat(response).isNotNull();

			String oldMethod = (String) response.get("old-method");
			assertThat(oldMethod).isNotNull();
			assertThat(oldMethod).isEqualTo(HttpMethod.POST.name());

			String method = (String) response.get("method");
			assertThat(method).isNotNull();
			assertThat(method).isEqualTo(HttpMethod.GET.name());
		});
	}

}
