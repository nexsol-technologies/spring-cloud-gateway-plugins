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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.servers.Server;
import reactor.core.publisher.Mono;

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

	private final ObjectMapper objectMapper;

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

		this.objectMapper = new ObjectMapper();
		this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

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
		c.setRewriteFunction(LinkedHashMap.class, String.class, rewriteServersWithGatewayUrl(config.getPath()));
		ModifyResponseBodyGatewayFilterFactory factory = new ModifyResponseBodyGatewayFilterFactory(this.messageReaders,
				this.messageBodyDecoders, this.messageBodyEncoders);

		ModifyResponseBodyGatewayFilterFactory.ModifyResponseGatewayFilter gatewayFilter = factory.new ModifyResponseGatewayFilter(
				c);
		return gatewayFilter;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private RewriteFunction<LinkedHashMap, String> rewriteServersWithGatewayUrl(String path) {
		return (serverWebExchange, openAPI) -> {

			if (openAPI == null) {
				return Mono.empty();
			}
			Server server = new Server();
			server.setUrl(this.apiGatewayUri.toString() + path);
			openAPI.put("servers", Collections.singletonList(server));
			try {
				String result = this.objectMapper.writeValueAsString(openAPI);
				return Mono.just(result);
			}
			catch (JsonProcessingException ex) {
				return Mono.empty();
			}
		};
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
