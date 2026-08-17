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

package ch.nexsol.service.sample;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The two ways this service reaches {@code service-b}, which is what the service graph
 * sample is about.
 * <p>
 * {@code /call-direct} goes straight to {@code service-b}: the gateway never sees that
 * call, and no counter of it can ever draw the edge. {@code /call-through-gateway} goes
 * through the gateway, which counts it and draws {@code service-a -> service-b}.
 * <p>
 * The calling service names itself in {@code X-Caller}, because these samples carry no
 * token. A deployment where {@code service-a} presents a token of its own needs none of
 * that: the {@code azp} claim names it, and the gateway reads it by default.
 */
@RestController
@Tag(name = "sample service A", description = "The two flows towards service-b.")
public class ServiceBCallerController {

	private static final String CALLER_HEADER = "X-Caller";

	private final WebClient webClient;

	private final String directUrl;

	private final String gatewayUrl;

	/**
	 * Creates the controller.
	 * @param builder the application web client builder
	 * @param directUrl the address of service-b itself
	 * @param gatewayUrl the address of service-b behind the gateway
	 */
	public ServiceBCallerController(WebClient.Builder builder,
			@Value("${sample.service-b.direct-url:http://localhost:8081/data}") String directUrl,
			@Value("${sample.service-b.gateway-url:http://localhost:8181/service-b/data}") String gatewayUrl) {
		this.webClient = builder.build();
		this.directUrl = directUrl;
		this.gatewayUrl = gatewayUrl;
	}

	/**
	 * Calls service-b directly, so the call never transits the gateway.
	 * @return what service-b answered
	 */
	@GetMapping("/call-direct")
	@Operation(description = "Calls service-b directly. Invisible to the gateway, and to every source but Tempo.")
	public Mono<Map<String, Object>> callDirect() {
		return call(this.directUrl);
	}

	/**
	 * Calls service-b through the gateway, which is what puts the edge in the graph.
	 * @return what service-b answered
	 */
	@GetMapping("/call-through-gateway")
	@Operation(description = "Calls service-b through the gateway, which counts the call and draws the edge.")
	public Mono<Map<String, Object>> callThroughGateway() {
		return call(this.gatewayUrl);
	}

	private Mono<Map<String, Object>> call(String url) {
		return this.webClient.get()
			.uri(url)
			.header(CALLER_HEADER, "service-a")
			.retrieve()
			.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
			})
			.map((answer) -> Map.of("calledBy", "service-a", "url", url, "answer", answer));
	}

}
