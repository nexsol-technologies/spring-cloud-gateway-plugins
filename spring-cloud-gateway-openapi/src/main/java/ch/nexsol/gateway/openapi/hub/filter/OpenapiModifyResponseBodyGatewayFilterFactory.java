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

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(PATH_KEY);
	}

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

	public static class Config {

		private String path;

		public String getPath() {
			return this.path;
		}

		public Config setPath(String path) {
			Assert.hasText(path, "path must have a value");
			this.path = path;
			return this;
		}

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
			String result;
			try {
				result = this.objectMapper.writeValueAsString(openAPI);
				return Mono.just(result);
			}
			catch (JsonProcessingException ex) {
				return Mono.empty();
			}
		};
	}

}
