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

package ch.nexsol.gateway.oauth2.filter;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.mockwebserver.MockWebServer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

@SpringBootConfiguration
@ComponentScan
@EnableAutoConfiguration
public class SpringAppConfiguration {

	public static void main(String[] args) {
		SpringApplication.run(SpringAppConfiguration.class, args);
	}

	@Bean
	WebClient webClient() {
		return WebClient.builder().build();
	}

	// The gateway filters (AuthorizationToken, BasicAuth exchange) perform their own
	// authorization logic, so the endpoints under test must not be blocked by the default
	// Spring Security chain (which secures everything by default on Spring Boot 4).
	@Bean
	SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
		http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
		http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
		return http.build();
	}

	@Bean
	CacheManager cacheManager() {
		return new ConcurrentMapCacheManager("basicauth-token-exchange.cache");
	}

	@Bean(destroyMethod = "shutdown")
	MockWebServer mockWebServer() throws IOException {
		MockWebServer mockOAuthServer = new MockWebServer();
		mockOAuthServer.start();
		return mockOAuthServer;
	}

	@Bean
	DynamicPropertyRegistrar apiPropertiesRegistrar(MockWebServer mockWebServer) {
		return (registry) -> registry.add("custom.port", mockWebServer::getPort);
	}

	@RestController
	@RequestMapping
	public class Controller {

		@RequestMapping(path = { "/authorization-token", "/authorization-token-all" },
				method = { RequestMethod.GET, RequestMethod.POST }, produces = MediaType.APPLICATION_JSON_VALUE)
		public Map<String, Object> authorizationHeader(ServerWebExchange exchange) {
			Map<String, Object> result = new HashMap<>();
			result.put("headers", toMap(exchange));
			return result;
		}

		@RequestMapping(path = { "/api/resource", "/actuator/health", "/ws/test" },
				method = { RequestMethod.GET, RequestMethod.POST }, produces = MediaType.APPLICATION_JSON_VALUE)
		public Map<String, Object> basicAuthToAccessToken(ServerWebExchange exchange) {
			Map<String, Object> result = new HashMap<>();
			result.put("headers", toMap(exchange));
			return result;
		}

		// As of Spring Framework 7, HttpHeaders no longer implements Map, so it can no
		// longer be serialized directly as a name/value map. Echo every request header
		// explicitly so the tests can assert on the forwarded Authorization header.
		private Map<String, List<String>> toMap(ServerWebExchange exchange) {
			Map<String, List<String>> headers = new LinkedHashMap<>();
			exchange.getRequest()
				.getHeaders()
				.headerSet()
				.forEach((entry) -> headers.put(entry.getKey(), entry.getValue()));
			return headers;
		}

	}

}
