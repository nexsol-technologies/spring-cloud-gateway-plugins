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

package ch.nexsol.gateway.sample.routesall;

import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Gateway sample combining every route source of the {@code spring-cloud-gateway-routes}
 * plugin — database, files, Config Server and OpenAPI contract — aggregated into a single
 * route locator, with {@code routes-security} letting a route declare itself public.
 */
@SpringBootApplication
public class RoutesAllGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(RoutesAllGatewayApplication.class, args);
	}

	/**
	 * Authenticates everything the gateway routes, which is what gives
	 * {@code routes-security} something to bypass: a route whose metadata carries
	 * {@code public: true} is served by the permissive chain the plugin registers ahead
	 * of this one, and never reaches it.
	 * <p>
	 * The shell and the routes API stay open so the sample can be browsed; the UI plugin
	 * would otherwise be behind Basic credentials on every fragment HTMX loads.
	 * @param http the security configuration to build the chain from
	 * @return the security filter chain
	 */
	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.authorizeExchange((spec) -> {
			spec.pathMatchers("/ui/**", "/api/gateway/**", "/actuator/**").permitAll();
			spec.anyExchange().authenticated();
		});
		http.httpBasic(withDefaults());
		http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
		http.logout(ServerHttpSecurity.LogoutSpec::disable);
		return http.build();
	}

	@Bean
	BCryptPasswordEncoder bCryptPasswordEncoder() {
		return new BCryptPasswordEncoder(5);
	}

	@Bean
	MapReactiveUserDetailsService userDetailsService(BCryptPasswordEncoder passwordEncoder) {
		List<UserDetails> users = List.of(User.withUsername("user")
			.passwordEncoder(passwordEncoder::encode)
			.password("user")
			.roles("READ")
			.build());
		return new MapReactiveUserDetailsService(users);
	}

}
