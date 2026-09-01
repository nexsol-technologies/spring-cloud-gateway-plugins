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

package ch.nexsol.gateway.sample.filters;

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
 * Gateway sample exercising the {@code spring-cloud-gateway-filters} plugin: the
 * {@code Authorization}, {@code ConvertHttpMethod}, {@code Maintenance} and
 * {@code CorrelationId} filters.
 */
@SpringBootApplication
public class FiltersGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(FiltersGatewayApplication.class, args);
	}

	/**
	 * The {@code Authorization} filter checks the authorities of the authenticated
	 * principal, so the sample authenticates the caller with Basic credentials first.
	 * Every other path is left open, so the {@code ConvertHttpMethod} and
	 * {@code CorrelationId} routes are reachable without credentials.
	 * <p>
	 * The maintenance paths are left open too, and still recognise {@code admin:admin}:
	 * Basic authentication runs on every path, so credentials sent to a permitted path
	 * populate the principal the {@code Maintenance} exemption is read from.
	 * @param http the security configuration to build the chain from
	 * @return the security filter chain
	 */
	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.authorizeExchange((spec) -> {
			spec.pathMatchers("/authorization/**", "/authorization-ko/**").authenticated();
			spec.anyExchange().permitAll();
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

	/**
	 * The two accounts the sample authenticates with: {@code user:user} carries
	 * {@code ROLE_READ}, {@code admin:admin} carries {@code ROLE_ADMIN}.
	 * @param passwordEncoder the encoder hashing the sample passwords
	 * @return the in-memory user details service
	 */
	@Bean
	MapReactiveUserDetailsService userDetailsService(BCryptPasswordEncoder passwordEncoder) {
		List<UserDetails> users = List.of(
				User.withUsername("user")
					.passwordEncoder(passwordEncoder::encode)
					.password("user")
					.roles("READ")
					.build(),
				User.withUsername("admin")
					.passwordEncoder(passwordEncoder::encode)
					.password("admin")
					.roles("ADMIN")
					.build());
		return new MapReactiveUserDetailsService(users);
	}

}
