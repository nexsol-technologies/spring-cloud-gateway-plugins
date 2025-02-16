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

package ch.nexsol.gateway.openapi.hub;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

public class OpenapiService {

	private static final Logger LOG = LoggerFactory.getLogger(OpenapiService.class);

	public static final String METADATA_SERVICE_INSTANCE_OPENAPI_PATH_KEY = "openapi_path";

	public static final String METADATA_SERVICE_INSTANCE_OPENAPI_CONTENT_TYPE_KEY = "openapi_content-type";

	private final ReactiveDiscoveryClient discoveryClient;

	private final WebClient webClient;

	private final List<String> paths = List.of("/v3/api-docs.json", "/v3/api-docs.yaml", "/v3/api-docs");

	public OpenapiService(ReactiveDiscoveryClient discoveryClient) {
		this.discoveryClient = discoveryClient;
		this.webClient = WebClient.builder().build();
	}

	public Mono<OpenapiDiscover> discoverOpenapiUrl(String routeId, RouteDefinition routeDefinition) {
		return this.discoveryClient.getInstances(routeId)
			.last()
			.flatMap((si) -> discoverOpenapiUrl(si, routeDefinition));
	}

	private Mono<OpenapiDiscover> discoverOpenapiUrl(ServiceInstance serviceInstance, RouteDefinition routeDefinition) {

		Flux<String> paths = Flux.fromIterable(this.paths);
		String openapiPath = serviceInstance.getMetadata().getOrDefault(METADATA_SERVICE_INSTANCE_OPENAPI_PATH_KEY, "");
		if (StringUtils.hasText(openapiPath)) {
			paths = Flux.just(openapiPath);
		}

		return paths.flatMap((path) -> callOpenapi(serviceInstance.getUri(), path, routeDefinition))
			.next()
			.map((openapiResponse) -> {
				if (!serviceInstance.getMetadata().containsKey(METADATA_SERVICE_INSTANCE_OPENAPI_PATH_KEY)) {
					serviceInstance.getMetadata()
						.put(METADATA_SERVICE_INSTANCE_OPENAPI_PATH_KEY, openapiResponse.path());
				}
				if (!serviceInstance.getMetadata().containsKey(METADATA_SERVICE_INSTANCE_OPENAPI_CONTENT_TYPE_KEY)) {
					serviceInstance.getMetadata()
						.put(METADATA_SERVICE_INSTANCE_OPENAPI_CONTENT_TYPE_KEY,
								openapiResponse.contentType().map((m) -> m.toString()).orElse(""));
				}

				routeDefinition.setMetadata(new LinkedHashMap<>(serviceInstance.getMetadata()));
				return openapiResponse;
			});
	}

	private Mono<OpenapiDiscover> callOpenapi(URI uri, String path, RouteDefinition routeDefinition) {
		return this.webClient.get().uri(uri + path).exchangeToMono((response) -> {
			if (response.statusCode().equals(HttpStatus.OK)) {
				LOG.debug("url {} found for route {} : {}", uri + path, routeDefinition, response.statusCode());
				return response.bodyToMono(byte[].class)
					.map(ByteArrayResource::new)
					.map((byteResource) -> new OpenapiDiscover(path, response.headers().contentType(), byteResource,
							routeDefinition));
			}
			else {
				LOG.debug("url {} not found for route {} : {}", uri + path, routeDefinition, response.statusCode());
				return Mono.empty();
			}
		}).onErrorComplete();
	}

	public record OpenapiDiscover(String path, Optional<MediaType> contentType, Resource resource,
			RouteDefinition routeDefinition) {
	}

}
