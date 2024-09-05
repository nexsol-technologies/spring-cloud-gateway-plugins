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

package ch.nexsol.gateway.filter;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * @see https://github.com/spring-cloud/spring-cloud-gateway/blob/main/spring-cloud-gateway-server/src/test/java/org/springframework/cloud/gateway/test/BaseWebClientTests.java
 */
public class BaseWebClientTests {

	protected String baseUri;

	@LocalServerPort
	protected int port = 0;

	protected WebTestClient testClient;

	@BeforeEach
	public void setup() throws Exception {
		setup(new ReactorClientHttpConnector(), "http://localhost:" + this.port);
	}

	protected void setup(ClientHttpConnector httpConnector, String baseUri) {
		this.baseUri = baseUri;
		this.testClient = WebTestClient.bindToServer(httpConnector).baseUrl(this.baseUri).build();
	}

}
