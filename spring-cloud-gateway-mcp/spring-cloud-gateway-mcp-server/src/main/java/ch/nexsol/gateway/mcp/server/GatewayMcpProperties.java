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

import ch.nexsol.gateway.commons.security.PluginSecurityProperties;

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

		private final PluginSecurityProperties.User user = new PluginSecurityProperties.User();

		private final OAuth2 oauth2 = new OAuth2();

		public PluginSecurityProperties.User getUser() {
			return this.user;
		}

		public OAuth2 getOauth2() {
			return this.oauth2;
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

		private final PluginSecurityProperties.ResourceServer resourceserver = new PluginSecurityProperties.ResourceServer();

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

		public PluginSecurityProperties.ResourceServer getResourceserver() {
			return this.resourceserver;
		}

	}

}
