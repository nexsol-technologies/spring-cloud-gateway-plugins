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

package ch.nexsol.gateway.mcp.server.oauth2;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * The MCP endpoint seen as an OAuth 2.0 protected resource (RFC 9728): the document that
 * names the authorization servers a client may obtain a token from, where that document
 * is served, and the {@code WWW-Authenticate} challenge pointing a client at it.
 * <p>
 * The resource identifier is the URL the clients connect to. Unless the application names
 * it, it is derived from the request: a gateway behind a proxy that rewrites the host
 * either configures {@code server.forward-headers-strategy} or names the resource.
 */
public final class ProtectedResource {

	/**
	 * The well-known path of RFC 9728. The document is served there and, for a resource
	 * with a path, under that path as well: a client reads the URL the challenge names,
	 * but one probing on its own tries the path form first and the root form next.
	 */
	public static final String WELL_KNOWN_PATH = "/.well-known/oauth-protected-resource";

	private static final String RESOURCE_METADATA = "resource_metadata";

	private final String resource;

	private final String endpoint;

	private final List<String> authorizationServers;

	private final List<String> scopes;

	/**
	 * Creates the protected resource.
	 * @param resource the resource identifier the application names, or {@code null} to
	 * derive it from the request
	 * @param endpoint the path of the endpoint the clients connect to
	 * @param authorizationServers the issuers a client may obtain a token from
	 * @param scopes the scopes a client may ask for, empty to leave them unsaid
	 */
	public ProtectedResource(String resource, String endpoint, List<String> authorizationServers, List<String> scopes) {
		Assert.hasText(endpoint, "endpoint must not be empty");
		Assert.notEmpty(authorizationServers, "authorizationServers must not be empty");
		if (StringUtils.hasText(resource)) {
			URI uri = URI.create(resource);
			Assert.isTrue(uri.isAbsolute() && uri.getRawAuthority() != null,
					() -> "resource must be an absolute URL, such as https://gateway.example.com/mcp, got '" + resource
							+ "'");
		}
		this.resource = StringUtils.hasText(resource) ? resource : null;
		this.endpoint = endpoint;
		this.authorizationServers = List.copyOf(authorizationServers);
		this.scopes = List.copyOf(scopes);
	}

	/**
	 * Returns the paths the metadata document is served on.
	 * @return the well-known path, and its path form when the resource has a path
	 */
	public List<String> metadataPaths() {
		String path = path();
		return path.isEmpty() ? List.of(WELL_KNOWN_PATH) : List.of(WELL_KNOWN_PATH, WELL_KNOWN_PATH + path);
	}

	/**
	 * Returns the resource identifier a token must be issued for.
	 * @param request the URI of the request being answered
	 * @return the identifier named by the application, or the endpoint at the origin of
	 * the request
	 */
	public String resource(URI request) {
		return (this.resource != null) ? this.resource : origin(request) + this.endpoint;
	}

	/**
	 * Returns the URL of the metadata document.
	 * @param request the URI of the request being answered
	 * @return the URL the challenge names
	 */
	public String metadataUrl(URI request) {
		String origin = (this.resource != null) ? origin(URI.create(this.resource)) : origin(request);
		return origin + WELL_KNOWN_PATH + path();
	}

	/**
	 * Returns the metadata document.
	 * @param request the URI of the request being answered
	 * @return the claims of RFC 9728, in the order they are listed there
	 */
	public Map<String, Object> metadata(URI request) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("resource", resource(request));
		metadata.put("authorization_servers", this.authorizationServers);
		if (!this.scopes.isEmpty()) {
			metadata.put("scopes_supported", this.scopes);
		}
		metadata.put("bearer_methods_supported", List.of("header"));
		return metadata;
	}

	/**
	 * Returns the {@code WWW-Authenticate} value of a {@code 401}, keeping whatever the
	 * chain already said about the token and adding where the metadata is.
	 * @param existing the value the chain set, or {@code null}
	 * @param request the URI of the request being answered
	 * @return a Bearer challenge naming the metadata URL
	 */
	public String challenge(String existing, URI request) {
		String parameter = RESOURCE_METADATA + "=\"" + metadataUrl(request) + "\"";
		if (existing == null || !existing.startsWith("Bearer")) {
			return "Bearer " + parameter;
		}
		if (existing.contains(RESOURCE_METADATA)) {
			return existing;
		}
		String challenge = existing.trim();
		return challenge.equals("Bearer") ? "Bearer " + parameter : challenge + ", " + parameter;
	}

	private String path() {
		if (this.resource == null) {
			return this.endpoint;
		}
		String path = URI.create(this.resource).getRawPath();
		return (path != null) ? path : "";
	}

	private static String origin(URI uri) {
		return uri.getScheme() + "://" + uri.getRawAuthority();
	}

}
