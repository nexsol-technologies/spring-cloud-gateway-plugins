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

package ch.nexsol.gateway.passivescan;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import ch.nexsol.gateway.passivescan.model.HttpExchange;
import ch.nexsol.gateway.passivescan.scanner.ExcessiveDataExposureScanner;
import ch.nexsol.gateway.passivescan.scanner.VerboseErrorScanner;
import ch.nexsol.gateway.passivescan.webfilter.CapturingServerHttpResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

class PassiveScanBodyTests {

	private static HttpExchange withBody(String body, HttpStatus status) {
		return new HttpExchange(Instant.now(), "GET", "https", "/api/x", new LinkedMultiValueMap<>(),
				HttpHeaders.readOnlyHttpHeaders(new HttpHeaders()), HttpHeaders.readOnlyHttpHeaders(new HttpHeaders()),
				status, "route-x", "10.0.0.1", body);
	}

	@Test
	void verboseErrorBodyIsFlagged() {
		HttpExchange ex = withBody("{\"trace\":\"at org.springframework.web...\"}", HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(new VerboseErrorScanner().inspect(ex)).hasSize(1);
	}

	@Test
	void bodyAwareScannerIsNoopWithoutBody() {
		HttpExchange ex = new HttpExchange(Instant.now(), "GET", "https", "/api/x", new LinkedMultiValueMap<>(),
				HttpHeaders.readOnlyHttpHeaders(new HttpHeaders()), HttpHeaders.readOnlyHttpHeaders(new HttpHeaders()),
				HttpStatus.OK, "route-x", "10.0.0.1");
		assertThat(new VerboseErrorScanner().inspect(ex)).isEmpty();
		assertThat(new ExcessiveDataExposureScanner().inspect(ex)).isEmpty();
	}

	@Test
	void privateKeyInBodyIsFlagged() {
		HttpExchange ex = withBody("-----BEGIN RSA PRIVATE KEY-----\nMIIE...", HttpStatus.OK);
		assertThat(new ExcessiveDataExposureScanner().inspect(ex)).hasSize(1);
	}

	@Test
	void capturingResponseCopiesBodyWithoutConsumingIt() {
		MockServerHttpResponse delegate = new MockServerHttpResponse();
		CapturingServerHttpResponse response = new CapturingServerHttpResponse(delegate, 1024);
		DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
		DataBuffer buffer = factory.wrap("hello world".getBytes(StandardCharsets.UTF_8));
		response.writeWith(Flux.just(buffer)).block();
		assertThat(response.capturedBody()).isEqualTo("hello world");
		assertThat(delegate.getBodyAsString().block()).isEqualTo("hello world");
	}

}
