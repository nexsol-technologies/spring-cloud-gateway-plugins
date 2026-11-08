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

package ch.nexsol.gateway.commons.security;

import java.util.ArrayList;
import java.util.List;

/**
 * How a plugin authenticates the callers of its own endpoints when no console governs
 * them, spelled as the console spells its own: a local user accepted over HTTP Basic, and
 * the issuer of the Bearer tokens it accepts.
 * <p>
 * Bound under the prefix of each plugin rather than once for the gateway, so that an
 * endpoint can carry credentials of its own.
 */
public class PluginSecurityProperties {

	private final User user = new User();

	private final OAuth2 oauth2 = new OAuth2();

	public User getUser() {
		return this.user;
	}

	public OAuth2 getOauth2() {
		return this.oauth2;
	}

	/**
	 * The local user accepted over HTTP Basic, held by the chain of the plugin alone.
	 * Without a password, there is no local user.
	 */
	public static class User {

		private String name = "admin";

		/**
		 * The password, either in clear text or prefixed with the id of the encoder that
		 * produced it, such as {@code {bcrypt}}.
		 */
		private String password;

		/**
		 * The roles the user holds, without their {@code ROLE_} prefix.
		 */
		private List<String> roles = new ArrayList<>(List.of("ADMIN"));

		public String getName() {
			return this.name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getPassword() {
			return this.password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public List<String> getRoles() {
			return this.roles;
		}

		public void setRoles(List<String> roles) {
			this.roles = roles;
		}

	}

	/**
	 * How a Bearer token is validated.
	 */
	public static class OAuth2 {

		private final ResourceServer resourceserver = new ResourceServer();

		public ResourceServer getResourceserver() {
			return this.resourceserver;
		}

	}

	/**
	 * Spelled as Spring Boot spells it under
	 * {@code spring.security.oauth2.resourceserver}.
	 */
	public static class ResourceServer {

		private final Jwt jwt = new Jwt();

		public Jwt getJwt() {
			return this.jwt;
		}

	}

	/**
	 * The issuer and the audiences of the tokens.
	 */
	public static class Jwt {

		/**
		 * The issuer the Bearer tokens are validated against. Its keys are fetched on the
		 * first token that arrives, never at start-up. Unset, the decoder the application
		 * declared is used, if any.
		 */
		private String issuerUri;

		/**
		 * The audiences a token must carry one of, under the issuer above. Empty, any
		 * token of the issuer is accepted.
		 */
		private List<String> audiences = new ArrayList<>();

		public String getIssuerUri() {
			return this.issuerUri;
		}

		public void setIssuerUri(String issuerUri) {
			this.issuerUri = issuerUri;
		}

		public List<String> getAudiences() {
			return this.audiences;
		}

		public void setAudiences(List<String> audiences) {
			this.audiences = audiences;
		}

	}

}
