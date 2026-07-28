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

package ch.nexsol.gateway.sample.secured;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Gateway sample combining the three plugins a secured gateway uses together:
 * {@code oauth2} validates the access token, {@code filters} checks the authorities the
 * route requires, and {@code routes-security} exempts the routes that declare themselves
 * public.
 * <p>
 * The three run at different moments, which is the point of the combination: the public
 * route matcher decides before authentication, the resource server authenticates, and the
 * gateway filters authorize once the route is known.
 */
@SpringBootApplication
public class SecuredGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(SecuredGatewayApplication.class, args);
	}

	/**
	 * Everything is authenticated with a Bearer token, except what a route flagged public
	 * covers — those requests are served by the chain {@code routes-security} registers
	 * ahead of this one and never reach it.
	 * @param http the security configuration to build the chain from
	 * @return the security filter chain
	 */
	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.authorizeExchange((spec) -> {
			spec.pathMatchers("/ui/**", "/actuator/**").permitAll();
			spec.anyExchange().authenticated();
		});
		http.oauth2ResourceServer((oauth2) -> oauth2.jwt(Customizer.withDefaults()));
		return http.build();
	}

}
