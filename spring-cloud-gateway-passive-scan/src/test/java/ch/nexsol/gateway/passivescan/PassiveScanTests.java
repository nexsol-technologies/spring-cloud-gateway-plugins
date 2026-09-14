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

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ch.nexsol.gateway.passivescan.engine.PassiveScanEngine;
import ch.nexsol.gateway.passivescan.model.Finding;
import ch.nexsol.gateway.passivescan.model.HttpExchange;
import ch.nexsol.gateway.passivescan.model.Severity;
import ch.nexsol.gateway.passivescan.scanner.CorsScanner;
import ch.nexsol.gateway.passivescan.scanner.JwtWeaknessScanner;
import ch.nexsol.gateway.passivescan.scanner.SecurityHeadersScanner;
import ch.nexsol.gateway.passivescan.scanner.SensitiveQueryScanner;
import ch.nexsol.gateway.passivescan.store.InMemoryFindingStore;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

class PassiveScanTests {

	private static HttpExchange exchange(HttpHeaders request, HttpHeaders response, HttpStatusCode status,
			String scheme, MultiValueMap<String, String> query) {
		return new HttpExchange(Instant.now(), "GET", scheme, "/api/x",
				(query != null) ? query : new LinkedMultiValueMap<>(), HttpHeaders.readOnlyHttpHeaders(request),
				HttpHeaders.readOnlyHttpHeaders(response), status, "route-x", "10.0.0.1");
	}

	@Test
	void securityHeadersMissingAreFlagged() {
		HttpExchange ex = exchange(new HttpHeaders(), new HttpHeaders(), HttpStatus.OK, "http", null);
		List<Finding> findings = new SecurityHeadersScanner().inspect(ex);
		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).severity()).isEqualTo(Severity.MEDIUM);
	}

	@Test
	void securityHeadersPresentAreClean() {
		HttpHeaders response = new HttpHeaders();
		response.set("X-Content-Type-Options", "nosniff");
		response.set("Content-Security-Policy", "default-src 'self'");
		HttpExchange ex = exchange(new HttpHeaders(), response, HttpStatus.OK, "http", null);
		assertThat(new SecurityHeadersScanner().inspect(ex)).isEmpty();
	}

	@Test
	void corsWildcardWithCredentialsIsCritical() {
		HttpHeaders response = new HttpHeaders();
		response.set("Access-Control-Allow-Origin", "*");
		response.set("Access-Control-Allow-Credentials", "true");
		HttpExchange ex = exchange(new HttpHeaders(), response, HttpStatus.OK, "https", null);
		List<Finding> findings = new CorsScanner().inspect(ex);
		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).severity()).isEqualTo(Severity.CRITICAL);
	}

	@Test
	void noCorsHeaderIsClean() {
		HttpExchange ex = exchange(new HttpHeaders(), new HttpHeaders(), HttpStatus.OK, "https", null);
		assertThat(new CorsScanner().inspect(ex)).isEmpty();
	}

	@Test
	void sensitiveQueryParameterIsFlagged() {
		MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
		query.add("access_token", "abc");
		HttpExchange ex = exchange(new HttpHeaders(), new HttpHeaders(), HttpStatus.OK, "https", query);
		List<Finding> findings = new SensitiveQueryScanner().inspect(ex);
		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).severity()).isEqualTo(Severity.HIGH);
	}

	@Test
	void jwtNoneAlgorithmIsCritical() {
		String header = base64("{\"alg\":\"none\"}");
		String payload = base64("{\"sub\":\"a\",\"exp\":9999999999}");
		HttpHeaders request = new HttpHeaders();
		request.set(HttpHeaders.AUTHORIZATION, "Bearer " + header + "." + payload + ".");
		HttpExchange ex = exchange(request, new HttpHeaders(), HttpStatus.OK, "https", null);
		List<Finding> findings = new JwtWeaknessScanner().inspect(ex);
		assertThat(findings).anyMatch((finding) -> finding.severity() == Severity.CRITICAL);
	}

	@Test
	void noBearerTokenIsClean() {
		HttpExchange ex = exchange(new HttpHeaders(), new HttpHeaders(), HttpStatus.OK, "https", null);
		assertThat(new JwtWeaknessScanner().inspect(ex)).isEmpty();
	}

	@Test
	void storeEvictsBeyondCapacityButKeepsCumulativeCounts() {
		InMemoryFindingStore store = new InMemoryFindingStore(2);
		for (int i = 0; i < 5; i++) {
			store.record(
					new Finding("s", ch.nexsol.gateway.passivescan.model.OwaspCategory.API8_SECURITY_MISCONFIGURATION,
							Severity.LOW, "t", "d", "GET", "/p", "r", Instant.now(), null));
		}
		assertThat(store.recent()).hasSize(2);
		assertThat(store.summary().total()).isEqualTo(5);
	}

	@Test
	void engineRunsScannersAndStoresFindings() {
		InMemoryFindingStore store = new InMemoryFindingStore(100);
		PassiveScanEngine engine = new PassiveScanEngine(List.of(new SecurityHeadersScanner()), store, 16);
		engine.scan(exchange(new HttpHeaders(), new HttpHeaders(), HttpStatus.OK, "http", null));
		assertThat(store.recent()).hasSize(1);
		engine.destroy();
	}

	@Test
	void engineScansConcurrentSubmissionsWithoutLoss() throws Exception {
		InMemoryFindingStore store = new InMemoryFindingStore(1000);
		PassiveScanEngine engine = new PassiveScanEngine(List.of(new SecurityHeadersScanner()), store, 1024);
		int count = 300;
		ExecutorService pool = Executors.newFixedThreadPool(8);
		for (int i = 0; i < count; i++) {
			pool.execute(
					() -> engine.submit(exchange(new HttpHeaders(), new HttpHeaders(), HttpStatus.OK, "http", null)));
		}
		pool.shutdown();
		pool.awaitTermination(5, TimeUnit.SECONDS);
		long deadline = System.currentTimeMillis() + 3000;
		while (store.summary().total() < count && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertThat(engine.dropped()).isZero();
		assertThat(store.summary().total()).isEqualTo(count);
		engine.destroy();
	}

	private static String base64(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
	}

}
