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

package ch.nexsol.gateway.oauth2.resourceserver.multitenancy;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests which issuers the resolver accepts and how it caches the manager it builds for
 * them.
 */
class TrustedIssuerJwtReactiveAuthenticationManagerResolverTests {

	private MockWebServer issuerServer;

	private final AtomicBoolean discoveryAvailable = new AtomicBoolean(true);

	@BeforeEach
	void startIssuer() throws IOException {
		this.issuerServer = new MockWebServer();
		this.issuerServer.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				if (!TrustedIssuerJwtReactiveAuthenticationManagerResolverTests.this.discoveryAvailable.get()) {
					return new MockResponse().setResponseCode(500);
				}
				return new MockResponse().setResponseCode(200)
					.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.setBody(metadata());
			}
		});
		this.issuerServer.start();
	}

	@AfterEach
	void stopIssuer() throws IOException {
		this.issuerServer.shutdown();
	}

	private String issuer() {
		// The discovery document must name the location it was fetched from, trailing
		// slash excluded, or Spring Security rejects it.
		return this.issuerServer.url("/").toString().replaceAll("/$", "");
	}

	private String metadata() {
		String issuer = issuer();
		return String.join("\n", "{", "\"issuer\": \"" + issuer + "\",", "\"jwks_uri\": \"" + issuer + "/jwks\",",
				"\"id_token_signing_alg_values_supported\": [\"RS256\"],", "\"subject_types_supported\": [\"public\"],",
				"\"response_types_supported\": [\"code\"],",
				"\"authorization_endpoint\": \"" + issuer + "/authorize\",",
				"\"token_endpoint\": \"" + issuer + "/token\"", "}");
	}

	private TrustedIssuerJwtReactiveAuthenticationManagerResolver resolver(String... trusted) {
		return new TrustedIssuerJwtReactiveAuthenticationManagerResolver(List.of(trusted)::contains, null);
	}

	@Test
	void resolvesNothingForAnUntrustedIssuer() {
		StepVerifier.create(resolver("https://trusted.example.com").resolve("https://attacker.example.com"))
			.verifyComplete();
	}

	@Test
	void neverReachesTheDiscoveryEndpointOfAnUntrustedIssuer() {
		resolver("https://trusted.example.com").resolve(issuer()).block();

		assertThat(this.issuerServer.getRequestCount()).isZero();
	}

	@Test
	void buildsAJwtManagerForATrustedIssuer() {
		ReactiveAuthenticationManager manager = resolver(issuer()).resolve(issuer()).block();

		assertThat(manager).isInstanceOf(JwtReactiveAuthenticationManager.class);
	}

	@Test
	void buildsTheManagerOnceAndReusesIt() {
		TrustedIssuerJwtReactiveAuthenticationManagerResolver resolver = resolver(issuer());

		ReactiveAuthenticationManager first = resolver.resolve(issuer()).block();
		ReactiveAuthenticationManager second = resolver.resolve(issuer()).block();

		assertThat(second).isSameAs(first);
		assertThat(this.issuerServer.getRequestCount()).isEqualTo(1);
	}

	@Test
	void forgetsAnIssuerWhoseDiscoveryFailedSoTheNextRequestRetries() {
		TrustedIssuerJwtReactiveAuthenticationManagerResolver resolver = resolver(issuer());
		this.discoveryAvailable.set(false);

		Mono<ReactiveAuthenticationManager> failing = resolver.resolve(issuer());
		StepVerifier.create(failing).verifyError();

		// The cached mono remembers the failure too; the entry must have been dropped or
		// the issuer would stay unusable for the life of the gateway.
		this.discoveryAvailable.set(true);
		assertThat(resolver.resolve(issuer()).block()).isInstanceOf(JwtReactiveAuthenticationManager.class);
	}

}
