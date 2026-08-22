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

package ch.nexsol.service.sample.c;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * A service behind the gateway that is a resource server and publishes an API contract:
 * the shape the OpenAPI hub aggregates, and the one a browser reaches through the gateway
 * when it opens the console's OpenAPI view.
 * <p>
 * It exists to answer one question the console cannot answer alone: what a service like
 * this puts in the response the gateway hands back to the browser. A {@code Set-Cookie}
 * from here arrives on the same origin as the console, and the browser cannot tell the
 * two apart when they share a cookie name.
 */
@SpringBootApplication
@RestController
public class ResourceServerApplication {

	/**
	 * Starts the service.
	 * @param args the command line arguments
	 */
	public static void main(String[] args) {
		SpringApplication.run(ResourceServerApplication.class, args);
	}

	/**
	 * The one endpoint the contract describes, so that the document the hub aggregates is
	 * not empty.
	 * @return the greeting
	 */
	@GetMapping("/service-c/data")
	@Operation(summary = "Answers whoever presents a token this service accepts")
	public Mono<String> data() {
		return Mono.just("service-c");
	}

	/**
	 * The contract of this service, carrying the {@code openIdConnect} scheme a caller
	 * obtains the token from.
	 * <p>
	 * An {@code openIdConnect} scheme declares no scope of its own: the console ticks the
	 * ones the {@code security} requirement lists, and offers only those the issuer
	 * advertises in {@code scopes_supported} &mdash; the sample authorization server
	 * advertises {@code openid} alone, Keycloak advertises the three.
	 * <p>
	 * Read through a gateway whose hub advertises its own issuers, this URL is replaced
	 * by the gateway's and this scheme becomes one scheme per tenant.
	 * @param issuerUri the issuer this service validates its tokens against
	 * @return the contract of this service
	 */
	@Bean
	OpenAPI openapi(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
		SecurityScheme scheme = new SecurityScheme().type(SecurityScheme.Type.OPENIDCONNECT)
			.openIdConnectUrl(issuerUri + "/.well-known/openid-configuration");
		return new OpenAPI().components(new Components().addSecuritySchemes("bearer-oidc", scheme))
			.addSecurityItem(new SecurityRequirement().addList("bearer-oidc", List.of("openid", "profile", "email")));
	}

	/**
	 * The chain of a resource server: its contract and its health are open, since the
	 * gateway reads them with nothing to present, and everything else asks for a token.
	 * @param http the reactive security builder
	 * @return the filter chain
	 */
	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
		http.authorizeExchange((spec) -> {
			spec.pathMatchers("/v3/api-docs/**", "/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui/**",
					"/swagger-ui.html", "/actuator/**")
				.permitAll();
			spec.anyExchange().authenticated();
		});
		http.oauth2ResourceServer((oauth2) -> oauth2.jwt(withDefaults()));
		return http.build();
	}

}
