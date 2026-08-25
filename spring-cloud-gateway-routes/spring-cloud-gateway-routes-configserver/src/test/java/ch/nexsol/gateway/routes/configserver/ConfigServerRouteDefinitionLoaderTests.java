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

package ch.nexsol.gateway.routes.configserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import ch.nexsol.gateway.routes.configserver.RoutesConfigServerProperties.ConfigServer;
import ch.nexsol.gateway.routes.files.RouteDefinitionFileParser;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link ConfigServerRouteDefinitionLoader} covering URL resolution from Config
 * Server coordinates, the reactive fetch/parse of YAML and JSON files, and error mapping.
 */
class ConfigServerRouteDefinitionLoaderTests {

	@Test
	void fetchesAndParsesYamlAndJsonUrlsInOrder() {
		List<String> requested = new ArrayList<>();
		RoutesConfigServerProperties properties = new RoutesConfigServerProperties();
		properties.setUrls(List.of("http://config:8888/gateway/default/main/orders.yaml",
				"http://config:8888/gateway/default/main/billing.json"));

		ConfigServerRouteDefinitionLoader loader = new ConfigServerRouteDefinitionLoader(webClient(requested),
				new RouteDefinitionFileParser(), properties);

		List<RouteDefinition> definitions = loader.load().collectList().block();

		assertThat(definitions).extracting(RouteDefinition::getId).containsExactly("orders_route", "billing_route");
		assertThat(definitions.get(0).getPredicates().get(0).getName()).isEqualTo("Path");
		assertThat(requested).containsExactly("http://config:8888/gateway/default/main/orders.yaml",
				"http://config:8888/gateway/default/main/billing.json");
	}

	@Test
	void resolvesConfigServerCoordinatesWithLabel() {
		RoutesConfigServerProperties properties = new RoutesConfigServerProperties();
		ConfigServer configServer = properties.getConfigServer();
		configServer.setUri("http://config:8888/");
		configServer.setName("gateway");
		configServer.setProfile("prod");
		configServer.setLabel("main");
		configServer.setFiles(List.of("routes/orders.yaml", "/routes/billing.yaml"));

		assertThat(ConfigServerRouteDefinitionLoader.resolveUrls(properties)).containsExactly(
				"http://config:8888/gateway/prod/main/routes/orders.yaml",
				"http://config:8888/gateway/prod/main/routes/billing.yaml");
	}

	@Test
	void resolvesConfigServerCoordinatesWithoutLabelAndMergesExplicitUrls() {
		RoutesConfigServerProperties properties = new RoutesConfigServerProperties();
		properties.setUrls(List.of("http://elsewhere/extra.yaml"));
		ConfigServer configServer = properties.getConfigServer();
		configServer.setUri("http://config:8888");
		configServer.setName("gateway");
		configServer.setFiles(List.of("orders.yaml"));

		assertThat(ConfigServerRouteDefinitionLoader.resolveUrls(properties))
			.containsExactly("http://elsewhere/extra.yaml", "http://config:8888/gateway/default/orders.yaml");
	}

	@Test
	void failsWhenConfigServerUriMissing() {
		RoutesConfigServerProperties properties = new RoutesConfigServerProperties();
		ConfigServer configServer = properties.getConfigServer();
		configServer.setName("gateway");
		configServer.setFiles(List.of("orders.yaml"));

		assertThatExceptionOfType(RouteConfigServerException.class)
			.isThrownBy(() -> ConfigServerRouteDefinitionLoader.resolveUrls(properties))
			.withMessageContaining("config-server.uri");
	}

	@Test
	void failsWhenConfigServerNameMissing() {
		RoutesConfigServerProperties properties = new RoutesConfigServerProperties();
		ConfigServer configServer = properties.getConfigServer();
		configServer.setUri("http://config:8888");
		configServer.setFiles(List.of("orders.yaml"));

		assertThatExceptionOfType(RouteConfigServerException.class)
			.isThrownBy(() -> ConfigServerRouteDefinitionLoader.resolveUrls(properties))
			.withMessageContaining("config-server.name");
	}

	@Test
	void returnsEmptyWhenNothingConfigured() {
		ConfigServerRouteDefinitionLoader loader = new ConfigServerRouteDefinitionLoader(webClient(new ArrayList<>()),
				new RouteDefinitionFileParser(), new RoutesConfigServerProperties());

		StepVerifier.create(loader.load()).verifyComplete();
	}

	@Test
	void wrapsFetchErrorInRouteConfigServerException() {
		RoutesConfigServerProperties properties = new RoutesConfigServerProperties();
		properties.setUrls(List.of("http://config:8888/gateway/default/main/missing.yaml"));
		ExchangeFunction notFound = (request) -> Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
		ConfigServerRouteDefinitionLoader loader = new ConfigServerRouteDefinitionLoader(
				WebClient.builder().exchangeFunction(notFound).build(), new RouteDefinitionFileParser(), properties);

		StepVerifier.create(loader.load()).expectError(RouteConfigServerException.class).verify();
	}

	@Test
	void resolvesTheUrlsOnEveryLoadSoARefreshedFileListIsPickedUp() {
		List<String> requested = new ArrayList<>();
		RoutesConfigServerProperties properties = new RoutesConfigServerProperties();
		ConfigServer configServer = properties.getConfigServer();
		configServer.setUri("http://config:8888");
		configServer.setName("gateway");
		configServer.setLabel("prod");
		configServer.setFiles(List.of("routes/orders.yaml"));

		ConfigServerRouteDefinitionLoader loader = new ConfigServerRouteDefinitionLoader(webClient(requested),
				new RouteDefinitionFileParser(), properties);

		assertThat(loader.load().collectList().block()).extracting(RouteDefinition::getId)
			.containsExactly("orders_route");

		// What /actuator/refresh does: ConfigurationPropertiesRebinder re-binds the
		// properties bean in place, while the loader bean itself is never recreated.
		configServer.setFiles(List.of("routes/orders.yaml", "routes/billing.json"));

		assertThat(loader.load().collectList().block()).extracting(RouteDefinition::getId)
			.containsExactly("orders_route", "billing_route");
		assertThat(requested).containsExactly("http://config:8888/gateway/default/prod/routes/orders.yaml",
				"http://config:8888/gateway/default/prod/routes/orders.yaml",
				"http://config:8888/gateway/default/prod/routes/billing.json");
	}

	@Test
	void stillFailsAtConstructionWhenCoordinatesAreIncomplete() {
		RoutesConfigServerProperties properties = new RoutesConfigServerProperties();
		ConfigServer configServer = properties.getConfigServer();
		configServer.setName("gateway");
		configServer.setFiles(List.of("orders.yaml"));

		assertThatExceptionOfType(RouteConfigServerException.class)
			.isThrownBy(() -> new ConfigServerRouteDefinitionLoader(webClient(new ArrayList<>()),
					new RouteDefinitionFileParser(), properties))
			.withMessageContaining("config-server.uri");
	}

	private WebClient webClient(List<String> requested) {
		ExchangeFunction exchange = (request) -> {
			requested.add(request.url().toString());
			String resource = request.url().toString().endsWith(".yaml") ? "/routes/orders-routes.yaml"
					: "/routes/billing-routes.json";
			return Mono.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
				.body(read(resource))
				.build());
		};
		return WebClient.builder().exchangeFunction(exchange).build();
	}

	private static String read(String resource) {
		try (InputStream input = ConfigServerRouteDefinitionLoaderTests.class.getResourceAsStream(resource)) {
			return new String(input.readAllBytes());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
