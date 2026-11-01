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

package ch.nexsol.gateway.mcp.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings of the MCP server plugin, under
 * {@code spring.cloud.gateway.server.webflux.mcp}.
 */
public class GatewayMcpProperties {

	private boolean enabled;

	private boolean writeEnabled;

	private boolean confirmWrites = true;

	private boolean securityChainEnabled = true;

	private final Security security = new Security();

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isWriteEnabled() {
		return this.writeEnabled;
	}

	public void setWriteEnabled(boolean writeEnabled) {
		this.writeEnabled = writeEnabled;
	}

	public boolean isConfirmWrites() {
		return this.confirmWrites;
	}

	public void setConfirmWrites(boolean confirmWrites) {
		this.confirmWrites = confirmWrites;
	}

	public boolean isSecurityChainEnabled() {
		return this.securityChainEnabled;
	}

	public void setSecurityChainEnabled(boolean securityChainEnabled) {
		this.securityChainEnabled = securityChainEnabled;
	}

	public Security getSecurity() {
		return this.security;
	}

	/**
	 * How the endpoint authenticates its callers, spelled as the console spells its own.
	 */
	public static class Security {

		private final User user = new User();

		private final OAuth2 oauth2 = new OAuth2();

		public User getUser() {
			return this.user;
		}

		public OAuth2 getOauth2() {
			return this.oauth2;
		}

	}

	/**
	 * The local user the endpoint accepts over HTTP Basic, held by the chain of the
	 * endpoint alone. Without a password, there is no local user and the chain
	 * authenticates against the user directory of the application.
	 */
	public static class User {

		private String name = "admin";

		private String password;

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
	 * How a Bearer token is validated, and what the endpoint publishes about itself as an
	 * OAuth 2.0 protected resource for a client to find the authorization server on its
	 * own.
	 */
	public static class OAuth2 {

		/**
		 * The resource identifier, the URL of the endpoint as the clients reach it.
		 * Unset, it is derived from each request.
		 */
		private String resource;

		/**
		 * The issuers a client may obtain a token from. Empty, the issuer the endpoint
		 * validates against is published, else the console's, else the application's;
		 * with none, nothing is published.
		 */
		private List<String> authorizationServers = new ArrayList<>();

		/**
		 * The scopes a client may ask for.
		 */
		private List<String> scopes = new ArrayList<>();

		private final ResourceServer resourceserver = new ResourceServer();

		public String getResource() {
			return this.resource;
		}

		public void setResource(String resource) {
			this.resource = resource;
		}

		public List<String> getAuthorizationServers() {
			return this.authorizationServers;
		}

		public void setAuthorizationServers(List<String> authorizationServers) {
			this.authorizationServers = authorizationServers;
		}

		public List<String> getScopes() {
			return this.scopes;
		}

		public void setScopes(List<String> scopes) {
			this.scopes = scopes;
		}

		public ResourceServer getResourceserver() {
			return this.resourceserver;
		}

		/**
		 * How the tokens the endpoint accepts are validated, spelled as Spring Boot
		 * spells it under {@code spring.security.oauth2.resourceserver}.
		 */
		public static class ResourceServer {

			private final Jwt jwt = new Jwt();

			public Jwt getJwt() {
				return this.jwt;
			}

			/**
			 * The issuer and the audiences of the tokens.
			 */
			public static class Jwt {

				/**
				 * The issuer the endpoint validates its Bearer tokens against. The keys
				 * are fetched from it on the first token that arrives, never at start-up.
				 * Unset, the decoder the application declared is used, if any.
				 */
				private String issuerUri;

				/**
				 * The audiences a token must carry one of, under the issuer above. Empty,
				 * any token of the issuer is accepted.
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

	}

}
