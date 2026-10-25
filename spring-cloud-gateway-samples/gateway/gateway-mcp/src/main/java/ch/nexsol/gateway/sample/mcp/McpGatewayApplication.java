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

package ch.nexsol.gateway.sample.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * A gateway serving its routes, traffic and findings to an MCP client.
 */
@SpringBootApplication
public class McpGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(McpGatewayApplication.class, args);
	}

	// The console's chain, contributed by the ui plugin and ordered ahead of this one,
	// governs /ui and the endpoints the plugins declare, /mcp among them. Everything else
	// is the traffic this gateway routes, open here so that a petstore tool call reaches
	// the routes generated from the contract. Without a chain of its own the application
	// would get Spring Boot's default one, which asks every route for a user of its own
	// and answers the credentials a tool call forwards with a 401.
	@Bean
	@Order(Ordered.LOWEST_PRECEDENCE)
	SecurityWebFilterChain routedTrafficWebFilterChain(ServerHttpSecurity http) {
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
		return http.build();
	}

}
