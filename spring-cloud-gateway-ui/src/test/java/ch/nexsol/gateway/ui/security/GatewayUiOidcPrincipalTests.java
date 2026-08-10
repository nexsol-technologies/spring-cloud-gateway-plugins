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

package ch.nexsol.gateway.ui.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the user the console builds out of an identity token when a roles claim is
 * configured: it carries the roles of the provider, and it is still named after the claim
 * the application chose.
 */
@SpringBootTest(properties = { "spring.cloud.gateway.server.webflux.ui.security.mode=authenticated",
		"spring.cloud.gateway.server.webflux.ui.security.roles-claim=realm_access.roles" })
class GatewayUiOidcPrincipalTests {

	@Autowired
	ReactiveOAuth2UserService<OidcUserRequest, OidcUser> userService;

	@Autowired
	ReactiveClientRegistrationRepository registrations;

	@Test
	void shouldNameThePrincipalAfterTheConfiguredClaimRatherThanTheSubject() {
		OidcUser user = load();
		/*
		 * Rebuilding the user without carrying the claim over falls back on 'sub', and
		 * the side menu of the console then reads the opaque identifier of the provider.
		 */
		assertThat(user.getName()).isEqualTo("operator");
		assertThat(user.getSubject()).isEqualTo("8b1c9e4a-0f2d-4c77-9a1e-6d2f0b3c5a71");
	}

	@Test
	void shouldAddTheRolesTheProviderGranted() {
		assertThat(load().getAuthorities().stream().map(GrantedAuthority::getAuthority)).contains("ROLE_ADMIN");
	}

	private OidcUser load() {
		Instant now = Instant.parse("2025-01-01T00:00:00Z");
		OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
			.issuedAt(now)
			.expiresAt(now.plusSeconds(600))
			.subject("8b1c9e4a-0f2d-4c77-9a1e-6d2f0b3c5a71")
			.claim("preferred_username", "operator")
			.claim("realm_access", Map.of("roles", List.of("admin")))
			.build();
		OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token", now,
				now.plusSeconds(600), Set.of("openid"));
		ClientRegistration registration = this.registrations.findByRegistrationId("keycloak").block();
		return this.userService.loadUser(new OidcUserRequest(registration, accessToken, idToken)).block();
	}

	/**
	 * A registration serving no user info endpoint, so the user is built from the
	 * identity token alone and the test reaches no provider.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class ClientRegistrationConfiguration {

		@Bean
		ReactiveClientRegistrationRepository clientRegistrationRepository() {
			return new InMemoryReactiveClientRegistrationRepository(ClientRegistration.withRegistrationId("keycloak")
				.clientName("Acme")
				.clientId("console")
				.clientSecret("secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.scope("openid")
				.authorizationUri("https://idp.example.com/auth")
				.tokenUri("https://idp.example.com/token")
				.jwkSetUri("https://idp.example.com/jwks")
				.userNameAttributeName("preferred_username")
				.build());
		}

	}

}
