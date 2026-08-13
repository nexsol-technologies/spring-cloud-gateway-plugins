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

package ch.nexsol.gateway.openapi.hub.filter;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyDecoder;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyEncoder;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.RewriteFunction;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.util.Assert;

/**
 * Gateway filter factory that rewrites a proxied OpenAPI document on its way out, so that
 * what a browser reads is what a browser can reach: the {@code servers} section
 * advertises the gateway URI (suffixed with the configured path) instead of the upstream
 * service address, and the OpenID Connect issuer of the security schemes is the
 * configured one instead of the address the service validates its own traffic against.
 */
public class OpenapiModifyResponseBodyGatewayFilterFactory
		extends AbstractGatewayFilterFactory<OpenapiModifyResponseBodyGatewayFilterFactory.Config> {

	private static final Logger LOG = LoggerFactory.getLogger(OpenapiModifyResponseBodyGatewayFilterFactory.class);

	private final JsonMapper jsonMapper;

	private final YAMLMapper yamlMapper;

	private final ModifyResponseBodyGatewayFilterFactory delegate;

	private final URI apiGatewayUri;

	private final Map<String, String> gatewayIssuers;

	/**
	 * Path key.
	 */
	public static final String PATH_KEY = "path";

	private static final String OPENID_CONNECT = "openIdConnect";

	private static final String OPENID_CONNECT_URL = "openIdConnectUrl";

	/**
	 * Returns the shortcut field order, allowing the filter to be configured with a
	 * single inline {@code path} argument.
	 * @return the ordered list of shortcut field names
	 */
	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(PATH_KEY);
	}

	/**
	 * Creates a new filter factory.
	 * @param messageReaders the HTTP message readers used to decode the response body
	 * @param messageBodyDecoders the available message body decoders
	 * @param messageBodyEncoders the available message body encoders
	 * @param apiGatewayUri the public gateway URI advertised in the rewritten OpenAPI
	 * servers
	 */
	public OpenapiModifyResponseBodyGatewayFilterFactory(List<HttpMessageReader<?>> messageReaders,
			Set<MessageBodyDecoder> messageBodyDecoders, Set<MessageBodyEncoder> messageBodyEncoders,
			URI apiGatewayUri) {
		this(messageReaders, messageBodyDecoders, messageBodyEncoders, apiGatewayUri, Map.of());
	}

	/**
	 * Creates a new filter factory that also replaces the OpenID Connect issuer of the
	 * documents it proxies.
	 * @param messageReaders the HTTP message readers used to decode the response body
	 * @param messageBodyDecoders the available message body decoders
	 * @param messageBodyEncoders the available message body encoders
	 * @param apiGatewayUri the public gateway URI advertised in the rewritten OpenAPI
	 * servers
	 * @param gatewayIssuers the OpenID Connect discovery URLs to advertise, by issuer
	 * name; empty to leave the security schemes of the documents as their services wrote
	 * them
	 */
	public OpenapiModifyResponseBodyGatewayFilterFactory(List<HttpMessageReader<?>> messageReaders,
			Set<MessageBodyDecoder> messageBodyDecoders, Set<MessageBodyEncoder> messageBodyEncoders, URI apiGatewayUri,
			Map<String, String> gatewayIssuers) {
		super(Config.class);

		this.jsonMapper = JsonMapper.builder().build();
		this.yamlMapper = YAMLMapper.builder().build();

		// Built once rather than on every apply(): apply() runs for every route carrying
		// this filter, on every route refresh, and the delegate holds nothing per route.
		this.delegate = new ModifyResponseBodyGatewayFilterFactory(messageReaders, messageBodyDecoders,
				messageBodyEncoders);

		this.apiGatewayUri = apiGatewayUri;
		this.gatewayIssuers = gatewayIssuers;
	}

	/**
	 * Builds a gateway filter that rewrites the OpenAPI {@code servers} section of the
	 * response body for the configured path.
	 * @param config the filter configuration holding the path to advertise
	 * @return the gateway filter
	 */
	@Override
	public GatewayFilter apply(Config config) {
		ModifyResponseBodyGatewayFilterFactory.Config c = new ModifyResponseBodyGatewayFilterFactory.Config();
		c.setRewriteFunction(byte[].class, byte[].class, rewriteServersWithGatewayUrl(config.getPath()));
		return this.delegate.new ModifyResponseGatewayFilter(c);
	}

	private RewriteFunction<byte[], byte[]> rewriteServersWithGatewayUrl(String path) {
		return (serverWebExchange, body) -> Mono.justOrEmpty(rewrite(body, path));
	}

	/**
	 * Rewrites the given OpenAPI document so it advertises the gateway: the
	 * {@code servers} section points at the gateway URI, and the OpenID Connect security
	 * schemes at the configured issuers. Both JSON and YAML documents are supported and
	 * re-serialized in their original format. A document that can be parsed as neither is
	 * returned unchanged rather than dropped, so the client always receives a usable
	 * body.
	 * @param body the raw OpenAPI document, may be {@code null} or empty
	 * @param path the path appended to the gateway URI in the rewritten servers
	 * @return the rewritten document, or the original body when it could not be rewritten
	 */
	byte[] rewrite(byte[] body, String path) {
		if (body == null || body.length == 0) {
			return body;
		}
		// JSON is a subset of YAML, so the format must be detected to be preserved: parse
		// (and later re-serialize) with the JSON mapper first, falling back to YAML.
		ObjectMapper mapper = this.jsonMapper;
		LinkedHashMap<String, Object> document = parse(this.jsonMapper, body);
		if (document == null) {
			mapper = this.yamlMapper;
			document = parse(this.yamlMapper, body);
		}
		if (document == null) {
			LOG.warn("Could not parse the OpenAPI document for path {} as JSON or YAML; forwarding it unchanged", path);
			return body;
		}
		// A path of "/" advertises the gateway root (used for statically configured
		// contracts whose routes keep the raw OpenAPI paths); trim the trailing slash to
		// avoid a double slash when the operation paths are appended.
		String serverUrl = this.apiGatewayUri.toString() + path;
		if (serverUrl.length() > 1 && serverUrl.endsWith("/")) {
			serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
		}
		document.put("servers", List.of(Map.of("url", serverUrl)));
		rewriteIssuers(document);
		return mapper.writeValueAsBytes(document);
	}

	/**
	 * Points every {@code openIdConnect} security scheme of the document at the
	 * configured issuers, replacing the one its service declared &mdash; typically an
	 * address only the cluster can resolve, where the document is read by a browser.
	 * <p>
	 * With a single issuer the schemes keep their names and only their discovery URL
	 * changes. With several, each scheme becomes one scheme per issuer, and every
	 * requirement referring to it becomes one alternative per issuer: an OpenAPI
	 * {@code security} list is a disjunction, so the console then offers the tenants as
	 * the choice they are.
	 */
	private void rewriteIssuers(Map<String, Object> document) {
		if (this.gatewayIssuers.isEmpty()) {
			return;
		}
		Map<String, Object> schemes = securitySchemes(document);
		if (schemes == null) {
			return;
		}
		List<String> openIdConnect = schemes.entrySet()
			.stream()
			.filter((scheme) -> scheme.getValue() instanceof Map<?, ?> definition
					&& OPENID_CONNECT.equals(definition.get("type")))
			.map(Map.Entry::getKey)
			.toList();
		if (openIdConnect.isEmpty()) {
			LOG.debug("No openIdConnect security scheme to point at the gateway issuers");
			return;
		}
		if (this.gatewayIssuers.size() == 1) {
			String url = this.gatewayIssuers.values().iterator().next();
			openIdConnect.forEach((name) -> asMap(schemes.get(name)).put(OPENID_CONNECT_URL, url));
			return;
		}
		for (String name : openIdConnect) {
			Map<String, Object> declared = asMap(schemes.remove(name));
			List<String> tenants = new ArrayList<>(this.gatewayIssuers.size());
			this.gatewayIssuers.forEach((issuer, url) -> {
				Map<String, Object> scheme = new LinkedHashMap<>(declared);
				scheme.put(OPENID_CONNECT_URL, url);
				schemes.put(name + "-" + issuer, scheme);
				tenants.add(name + "-" + issuer);
			});
			expandRequirements(document, name, tenants);
		}
	}

	/**
	 * Replaces every requirement naming the given scheme by one alternative per
	 * replacement, at the root of the document and on every operation that carries its
	 * own.
	 */
	private static void expandRequirements(Map<String, Object> document, String scheme, List<String> replacements) {
		document.computeIfPresent("security", (key, value) -> expand(value, scheme, replacements));
		if (!(document.get("paths") instanceof Map<?, ?> paths)) {
			return;
		}
		for (Object item : paths.values()) {
			if (item instanceof Map<?, ?> operations) {
				for (Object operation : operations.values()) {
					if (operation instanceof Map<?, ?> definition) {
						asMap(definition).computeIfPresent("security",
								(key, value) -> expand(value, scheme, replacements));
					}
				}
			}
		}
	}

	private static Object expand(Object requirements, String scheme, List<String> replacements) {
		if (!(requirements instanceof List<?> alternatives)) {
			return requirements;
		}
		List<Object> expanded = new ArrayList<>(alternatives.size());
		for (Object alternative : alternatives) {
			if (alternative instanceof Map<?, ?> required && required.containsKey(scheme)) {
				for (String replacement : replacements) {
					Map<String, Object> copy = new LinkedHashMap<>(asMap(alternative));
					copy.put(replacement, copy.remove(scheme));
					expanded.add(copy);
				}
			}
			else {
				expanded.add(alternative);
			}
		}
		return expanded;
	}

	private static Map<String, Object> securitySchemes(Map<String, Object> document) {
		if (document.get("components") instanceof Map<?, ?> components
				&& asMap(components).get("securitySchemes") instanceof Map<?, ?> schemes) {
			return asMap(schemes);
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static LinkedHashMap<String, Object> parse(ObjectMapper mapper, byte[] body) {
		try {
			return mapper.readValue(body, LinkedHashMap.class);
		}
		catch (JacksonException ex) {
			return null;
		}
	}

	/**
	 * Configuration for the {@link OpenapiModifyResponseBodyGatewayFilterFactory} filter.
	 */
	public static class Config {

		private String path;

		/**
		 * Returns the configured path appended to the gateway URI.
		 * @return the path
		 */
		public String getPath() {
			return this.path;
		}

		/**
		 * Sets the path to append to the gateway URI in the rewritten OpenAPI servers.
		 * @param path the path (must have text)
		 * @return this config, for chaining
		 */
		public Config setPath(String path) {
			Assert.hasText(path, "path must have a value");
			this.path = path;
			return this;
		}

	}

}
