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

package ch.nexsol.gateway.oauth2.properties;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties describing, for each configured client id, the token endpoint
 * used to exchange its Basic credentials for an OAuth 2.0 access token.
 * <p>
 * A client is declared either through {@code token-uris}, which carries nothing but the
 * endpoint, or through {@code clients}, which carries the endpoint plus the scopes to ask
 * for. The two forms are equivalent for a client needing no scope.
 */
@Validated
public class BasicAuthExchangeToAccessTokenProperties {

	private boolean enabled = true;

	private Map<@NotEmpty String, @NotNull URI> tokenUris = new HashMap<>();

	@Valid
	private Map<@NotEmpty String, @NotNull Client> clients = new HashMap<>();

	private boolean securityChainEnabled = true;

	private boolean credentialsInQueryParam;

	private String credentialsQueryParamName = "_auth";

	/**
	 * Whether the given user (client id) has a configured token endpoint, under either
	 * declaration form.
	 * @param user the client id to look up
	 * @return {@code true} if a token endpoint is configured for the user
	 */
	public boolean isUserConfigured(String user) {
		// Answered through the resolution the exchange itself uses, never through a
		// containsKey of its own: a user this returns true for but that resolves to
		// nothing would be taken away from the security chains of the application and
		// then forwarded with its raw credentials, unexchanged.
		return resolveClient(user).isPresent();
	}

	/**
	 * Resolve the exchange configuration of the given user (client id). A client declared
	 * under both forms is taken from {@code clients}, the richer of the two.
	 * @param user the client id to look up
	 * @return the client configuration, or empty when the user is not configured
	 */
	public Optional<Client> resolveClient(String user) {
		Client client = this.clients.get(user);
		if (client != null) {
			return Optional.of(client);
		}
		return Optional.ofNullable(this.tokenUris.get(user)).map(Client::of);
	}

	/**
	 * Whether the exchange is active at all. Turning it off leaves the configured clients
	 * in place and registers neither the filter nor its security chain, so a request
	 * carrying Basic credentials is forwarded as it came in.
	 * @return {@code true} when the exchange is active
	 */
	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * Set whether the exchange is active.
	 * @param enabled {@code false} to switch the exchange off
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Return the mapping of client id to token URI.
	 * @return the token URIs
	 */
	public Map<String, URI> getTokenUris() {
		return this.tokenUris;
	}

	/**
	 * Set the mapping of client id to token URI.
	 * @param tokenUris the token URIs to set
	 */
	public void setTokenUris(Map<String, URI> tokenUris) {
		this.tokenUris = tokenUris;
	}

	/**
	 * Return the mapping of client id to its full exchange configuration.
	 * @return the clients
	 */
	public Map<String, Client> getClients() {
		return this.clients;
	}

	/**
	 * Set the mapping of client id to its full exchange configuration.
	 * @param clients the clients to set
	 */
	public void setClients(Map<String, Client> clients) {
		this.clients = clients;
	}

	/**
	 * Whether the plugin contributes the security filter chain that lets the configured
	 * Basic credentials through Spring Security so they can be exchanged.
	 * @return {@code true} when the chain is contributed
	 */
	public boolean isSecurityChainEnabled() {
		return this.securityChainEnabled;
	}

	/**
	 * Set whether the plugin contributes its security filter chain.
	 * @param securityChainEnabled {@code false} to declare the chain in the application
	 * instead
	 */
	public void setSecurityChainEnabled(boolean securityChainEnabled) {
		this.securityChainEnabled = securityChainEnabled;
	}

	/**
	 * Whether Basic credentials are also accepted from a query parameter, for clients
	 * that cannot set an {@code Authorization} header. Off by default: credentials in a
	 * URL end up in access logs, proxy logs and browser history.
	 * @return {@code true} when the query parameter is read
	 */
	public boolean isCredentialsInQueryParam() {
		return this.credentialsInQueryParam;
	}

	/**
	 * Set whether Basic credentials are also accepted from a query parameter.
	 * @param credentialsInQueryParam {@code true} to read the query parameter
	 */
	public void setCredentialsInQueryParam(boolean credentialsInQueryParam) {
		this.credentialsInQueryParam = credentialsInQueryParam;
	}

	/**
	 * Return the name of the query parameter carrying the Base64 credentials.
	 * @return the query parameter name
	 */
	public String getCredentialsQueryParamName() {
		return this.credentialsQueryParamName;
	}

	/**
	 * Set the name of the query parameter carrying the Base64 credentials.
	 * @param credentialsQueryParamName the query parameter name to set
	 */
	public void setCredentialsQueryParamName(String credentialsQueryParamName) {
		this.credentialsQueryParamName = credentialsQueryParamName;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String toString() {
		return "BasicAuthExchangeToAccessTokenProperties [tokenUris=" + this.tokenUris + ", clients=" + this.clients
				+ "]";
	}

	/**
	 * The exchange configuration of a single client: the token endpoint to call and the
	 * scopes to ask for.
	 */
	public static class Client {

		@NotNull
		private URI tokenUri;

		private List<@NotEmpty String> scopes = new ArrayList<>();

		/**
		 * Create a client asking for no particular scope.
		 * @param tokenUri the token endpoint
		 * @return the client configuration
		 */
		public static Client of(URI tokenUri) {
			Client client = new Client();
			client.setTokenUri(tokenUri);
			return client;
		}

		/**
		 * Return the token endpoint of this client.
		 * @return the tokenUri
		 */
		public URI getTokenUri() {
			return this.tokenUri;
		}

		/**
		 * Set the token endpoint of this client.
		 * @param tokenUri the tokenUri to set
		 */
		public void setTokenUri(URI tokenUri) {
			this.tokenUri = tokenUri;
		}

		/**
		 * Return the scopes requested for this client, empty when the exchange asks for
		 * none.
		 * @return the scopes
		 */
		public List<String> getScopes() {
			return this.scopes;
		}

		/**
		 * Set the scopes requested for this client.
		 * @param scopes the scopes to set
		 */
		public void setScopes(List<String> scopes) {
			this.scopes = (scopes != null) ? scopes : new ArrayList<>();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public String toString() {
			return "Client [tokenUri=" + this.tokenUri + ", scopes=" + this.scopes + "]";
		}

	}

}
