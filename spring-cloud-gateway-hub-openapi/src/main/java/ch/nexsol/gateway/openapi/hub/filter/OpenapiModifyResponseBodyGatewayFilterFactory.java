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
 * Gateway filter factory that rewrites the {@code servers} section of a proxied OpenAPI
 * document so that it advertises the gateway URI (suffixed with the configured path)
 * instead of the upstream service address.
 */
public class OpenapiModifyResponseBodyGatewayFilterFactory
		extends AbstractGatewayFilterFactory<OpenapiModifyResponseBodyGatewayFilterFactory.Config> {

	private static final Logger LOG = LoggerFactory.getLogger(OpenapiModifyResponseBodyGatewayFilterFactory.class);

	private final JsonMapper jsonMapper;

	private final YAMLMapper yamlMapper;

	private final Set<MessageBodyDecoder> messageBodyDecoders;

	private final Set<MessageBodyEncoder> messageBodyEncoders;

	private final List<HttpMessageReader<?>> messageReaders;

	private final URI apiGatewayUri;

	/**
	 * Path key.
	 */
	public static final String PATH_KEY = "path";

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
		super(Config.class);

		this.jsonMapper = JsonMapper.builder().build();
		this.yamlMapper = YAMLMapper.builder().build();

		this.messageReaders = messageReaders;
		this.messageBodyDecoders = messageBodyDecoders;
		this.messageBodyEncoders = messageBodyEncoders;

		this.apiGatewayUri = apiGatewayUri;
	}

	/**
	 * Builds a gateway filter that rewrites the OpenAPI {@code servers} section of the
	 * response body for the configured path.
	 * @param config the filter configuration holding the path to advertise
	 * @return the gateway filter
	 */
	@Override
	public GatewayFilter apply(Config config) {
		org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory.Config c = new org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory.Config();
		c.setRewriteFunction(byte[].class, byte[].class, rewriteServersWithGatewayUrl(config.getPath()));
		ModifyResponseBodyGatewayFilterFactory factory = new ModifyResponseBodyGatewayFilterFactory(this.messageReaders,
				this.messageBodyDecoders, this.messageBodyEncoders);

		ModifyResponseBodyGatewayFilterFactory.ModifyResponseGatewayFilter gatewayFilter = factory.new ModifyResponseGatewayFilter(
				c);
		return gatewayFilter;
	}

	private RewriteFunction<byte[], byte[]> rewriteServersWithGatewayUrl(String path) {
		return (serverWebExchange, body) -> Mono.justOrEmpty(rewriteServers(body, path));
	}

	/**
	 * Rewrites the {@code servers} section of the given OpenAPI document so it advertises
	 * the gateway URI. Both JSON and YAML documents are supported and re-serialized in
	 * their original format. A document that can be parsed as neither is returned
	 * unchanged rather than dropped, so the client always receives a usable body.
	 * @param body the raw OpenAPI document, may be {@code null} or empty
	 * @param path the path appended to the gateway URI in the rewritten servers
	 * @return the rewritten document, or the original body when it could not be rewritten
	 */
	byte[] rewriteServers(byte[] body, String path) {
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
		return mapper.writeValueAsBytes(document);
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
