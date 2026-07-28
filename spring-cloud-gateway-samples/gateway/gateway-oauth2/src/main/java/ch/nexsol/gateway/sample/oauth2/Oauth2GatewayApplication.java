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

package ch.nexsol.gateway.sample.oauth2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Gateway sample exercising the {@code spring-cloud-gateway-oauth2} plugin: the
 * {@code AuthorizationToken} filter, the Basic-to-Bearer exchange web filter and the
 * multi-tenant resource server.
 */
@SpringBootApplication
public class Oauth2GatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(Oauth2GatewayApplication.class, args);
	}

	/**
	 * Every request is let through; a Bearer token, when there is one, is still parsed
	 * and validated by the resource server, which is what gives the
	 * {@code AuthorizationToken} filter the {@code Principal} it reads. Authorization is
	 * the filter's job here, route by route, rather than the chain's.
	 * <p>
	 * The Basic-to-Bearer chain is contributed by the plugin itself and runs ahead of
	 * this one, so nothing about it has to be declared.
	 * @param http the security configuration to build the chain from
	 * @return the security filter chain
	 */
	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
		http.oauth2ResourceServer((oauth2) -> oauth2.jwt(Customizer.withDefaults()));
		return http.build();
	}

}
