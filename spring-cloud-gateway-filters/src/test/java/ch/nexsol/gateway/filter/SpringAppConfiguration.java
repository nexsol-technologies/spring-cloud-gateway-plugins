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

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.MediaType;
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

	@RestController
	@RequestMapping()
	public class Controller {

		@RequestMapping(path = { "/authorization-token", "/authorization-token-all" },
				method = { RequestMethod.GET, RequestMethod.POST }, produces = MediaType.APPLICATION_JSON_VALUE)
		public Map<String, Object> authorizationHeader(ServerWebExchange exchange) {
			Map<String, Object> result = new HashMap<>();
			result.put("headers", exchange.getRequest().getHeaders());
			return result;
		}

		// not working with PostMapping and GetMapping
		@RequestMapping(path = { "/convert-http-method" }, method = { RequestMethod.POST, RequestMethod.GET },
				produces = MediaType.APPLICATION_JSON_VALUE)
		public Map<String, Object> post(ServerWebExchange exchange) {
			Map<String, Object> result = new HashMap<>();
			result.put("method", exchange.getRequest().getMethod().name());
			result.put("old-method", exchange.getRequest().getHeaders().getFirst("x-method"));
			return result;
		}

	}

}
