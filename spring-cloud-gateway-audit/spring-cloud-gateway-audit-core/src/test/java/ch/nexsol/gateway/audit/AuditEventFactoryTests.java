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

package ch.nexsol.gateway.audit;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventFactoryTests {

	private final AuditEventFactory factory = new AuditEventFactory(new AuditProperties());

	@Test
	void collectsRequestAndResponseAttributes() {
		MockServerWebExchange exchange = exchangeWithResponse(
				MockServerHttpRequest.get("/patient/99098875/alert-summaries")
					.header(HttpHeaders.ACCEPT, "application/json,text/plain,*/*")
					.remoteAddress(new InetSocketAddress("129.195.179.159", 40000))
					.build());

		Map<String, String> attributes = this.factory.create(exchange).block().attributes();

		assertThat(attributes).containsEntry(AuditAttributes.REQUEST_HEADER_ACCEPT, "application/json,text/plain,*/*")
			.containsEntry(AuditAttributes.REQUEST_HEADER_CONTENT_LENGTH, "-1")
			.containsEntry(AuditAttributes.REQUEST_HEADER_CONTENT_TYPE, AuditAttributes.UNKNOWN_VALUE)
			.containsEntry(AuditAttributes.REQUEST_IP, "129.195.179.159")
			.containsEntry(AuditAttributes.REQUEST_METHOD, "GET")
			.containsEntry(AuditAttributes.REQUEST_PARAMETERS, AuditAttributes.NONE_VALUE)
			.containsEntry(AuditAttributes.REQUEST_PATH, "/patient/99098875/alert-summaries")
			.containsEntry(AuditAttributes.RESPONSE_HEADER_CONTENT_LENGTH, "387")
			.containsEntry(AuditAttributes.RESPONSE_HEADER_CONTENT_TYPE, "application/hal+json;version=1")
			.containsEntry(AuditAttributes.RESPONSE_STATUS, "OK");
	}

	@Test
	void rendersMissingJwtAndTraceAsNone() {
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/patient/99098875/alert-summaries").build());

		Map<String, String> attributes = this.factory.create(exchange).block().attributes();

		assertThat(attributes).containsEntry(AuditAttributes.JWT_CLIENT_ID, AuditAttributes.NONE_VALUE)
			.containsEntry(AuditAttributes.JWT_IMPERSONATOR_USER_ID, AuditAttributes.NONE_VALUE)
			.containsEntry(AuditAttributes.JWT_IMPERSONATOR_USER_NAME, AuditAttributes.NONE_VALUE)
			.containsEntry(AuditAttributes.JWT_ISSUER_ID, AuditAttributes.NONE_VALUE)
			.containsEntry(AuditAttributes.JWT_USER_ID, AuditAttributes.NONE_VALUE)
			.containsEntry(AuditAttributes.TRACE_ID, AuditAttributes.NONE_VALUE)
			.containsEntry(AuditAttributes.SPAN_ID, AuditAttributes.NONE_VALUE);
	}

	@Test
	void collectsJwtAttributesFromPrincipal() {
		Jwt jwt = Jwt.withTokenValue("token")
			.header("alg", "none")
			.claim("preferred_username", "toto")
			.claim("azp", "dxxx")
			.issuer("https://keycloak/realms/collaborator")
			.build();
		ServerWebExchange exchange = withPrincipal(
				MockServerWebExchange.from(MockServerHttpRequest.get("/patient").build()),
				new JwtAuthenticationToken(jwt));

		Map<String, String> attributes = this.factory.create(exchange).block().attributes();

		assertThat(attributes).containsEntry(AuditAttributes.JWT_USER_ID, "toto")
			.containsEntry(AuditAttributes.JWT_CLIENT_ID, "dxxx")
			.containsEntry(AuditAttributes.JWT_ISSUER_ID, "https://keycloak/realms/collaborator")
			.containsEntry(AuditAttributes.JWT_IMPERSONATOR_USER_ID, AuditAttributes.NONE_VALUE);
	}

	@Test
	void usesBasicAuthUserAsUserId() {
		String basic = "Basic " + Base64.getEncoder().encodeToString("toto:secret".getBytes(StandardCharsets.UTF_8));
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/patient").header(HttpHeaders.AUTHORIZATION, basic).build());

		Map<String, String> attributes = this.factory.create(exchange).block().attributes();

		assertThat(attributes).containsEntry(AuditAttributes.JWT_USER_ID, "toto");
	}

	@Test
	void skipsDisabledGroups() {
		AuditProperties properties = new AuditProperties();
		properties.getGroups().setResponse(false);
		properties.getGroups().setJwt(false);
		AuditEventFactory disabled = new AuditEventFactory(properties);
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/patient").build());

		Map<String, String> attributes = disabled.create(exchange).block().attributes();

		assertThat(attributes).containsKey(AuditAttributes.REQUEST_PATH)
			.doesNotContainKey(AuditAttributes.RESPONSE_STATUS)
			.doesNotContainKey(AuditAttributes.JWT_USER_ID);
	}

	@Test
	void collectsImpersonatorFromActClaim() {
		Jwt jwt = Jwt.withTokenValue("token")
			.header("alg", "none")
			.claim("preferred_username", "toto")
			.claim("act", Map.of("sub", "admin", "preferred_username", "adminUser"))
			.issuer("https://keycloak/realms/collaborator")
			.build();
		ServerWebExchange exchange = withPrincipal(
				MockServerWebExchange.from(MockServerHttpRequest.get("/patient").build()),
				new JwtAuthenticationToken(jwt));

		Map<String, String> attributes = this.factory.create(exchange).block().attributes();

		assertThat(attributes).containsEntry(AuditAttributes.JWT_IMPERSONATOR_USER_ID, "admin")
			.containsEntry(AuditAttributes.JWT_IMPERSONATOR_USER_NAME, "adminUser");
	}

	@Test
	void fallsBackToClientIdAndSubjectClaims() {
		Jwt jwt = Jwt.withTokenValue("token")
			.header("alg", "none")
			.claim("client_id", "cid")
			.subject("sub-123")
			.issuer("https://issuer")
			.build();
		ServerWebExchange exchange = withPrincipal(
				MockServerWebExchange.from(MockServerHttpRequest.get("/patient").build()),
				new JwtAuthenticationToken(jwt));

		Map<String, String> attributes = this.factory.create(exchange).block().attributes();

		assertThat(attributes).containsEntry(AuditAttributes.JWT_CLIENT_ID, "cid")
			.containsEntry(AuditAttributes.JWT_USER_ID, "sub-123");
	}

	@Test
	void prefersXForwardedForForIp() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/patient")
			.header("X-Forwarded-For", "203.0.113.7, 70.41.3.18")
			.remoteAddress(new InetSocketAddress("10.0.0.1", 1234))
			.build());

		Map<String, String> attributes = this.factory.create(exchange).block().attributes();

		assertThat(attributes).containsEntry(AuditAttributes.REQUEST_IP, "203.0.113.7");
	}

	@Test
	void rendersNonStandardStatusAsNumericCode() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/patient").build());
		exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(299));

		Map<String, String> attributes = this.factory.create(exchange).block().attributes();

		assertThat(attributes).containsEntry(AuditAttributes.RESPONSE_STATUS, "299");
	}

	@Test
	void usesPrincipalNameWhenNeitherJwtNorBasic() {
		ServerWebExchange exchange = withPrincipal(
				MockServerWebExchange.from(MockServerHttpRequest.get("/patient").build()),
				new UsernamePasswordAuthenticationToken("svc", "n/a"));

		Map<String, String> attributes = this.factory.create(exchange).block().attributes();

		assertThat(attributes).containsEntry(AuditAttributes.JWT_USER_ID, "svc");
	}

	private MockServerWebExchange exchangeWithResponse(MockServerHttpRequest request) {
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		exchange.getResponse().setStatusCode(HttpStatus.OK);
		exchange.getResponse().getHeaders().setContentType(MediaType.parseMediaType("application/hal+json;version=1"));
		exchange.getResponse().getHeaders().setContentLength(387);
		return exchange;
	}

	private ServerWebExchange withPrincipal(ServerWebExchange exchange, Principal principal) {
		return new ServerWebExchangeDecorator(exchange) {
			@SuppressWarnings("unchecked")
			@Override
			public <T extends Principal> Mono<T> getPrincipal() {
				return Mono.just((T) principal);
			}
		};
	}

}
