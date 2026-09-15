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

package ch.nexsol.gateway.ui.routes;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exported document has to be configuration the gateway reads back as the routes it
 * was written from, which is what these assert: the property path, the shortcut form
 * where it survives the round trip, and the long form where it would not.
 */
class RouteDefinitionYamlTests {

	@Test
	void shouldNestTheRoutesUnderThePropertyPathTheGatewayReadsThemFrom() {
		String yaml = RouteDefinitionYaml.write(List.of(route("alpha", "http://alpha.example.com")));

		assertThat(read(yaml)).containsKey("spring");
		assertThat(routesOf(yaml)).hasSize(1);
		assertThat(routesOf(yaml).get(0)).containsEntry("id", "alpha").containsEntry("uri", "http://alpha.example.com");
	}

	@Test
	void shouldWritePositionalArgumentsInTheShortcutForm() {
		RouteDefinition route = route("alpha", "http://alpha.example.com");
		route.setPredicates(List.of(predicate("Path", Map.of("_genkey_0", "/alpha/**"))));
		route.setFilters(List.of(filter("StripPrefix", Map.of("_genkey_0", "1"))));

		Map<String, Object> written = routesOf(RouteDefinitionYaml.write(List.of(route))).get(0);

		assertThat(written.get("predicates")).isEqualTo(List.of("Path=/alpha/**"));
		assertThat(written.get("filters")).isEqualTo(List.of("StripPrefix=1"));
	}

	@Test
	void shouldOrderShortcutArgumentsByPositionRatherThanByName() {
		// '_genkey_10' comes before '_genkey_2' as text, and the shortcut is read by
		// position: sorting these as names would export a different route.
		Map<String, String> args = new LinkedHashMap<>();
		args.put("_genkey_10", "k");
		args.put("_genkey_2", "c");
		args.put("_genkey_0", "a");
		RouteDefinition route = route("alpha", "http://alpha.example.com");
		route.setPredicates(List.of(predicate("Many", args)));

		Map<String, Object> written = routesOf(RouteDefinitionYaml.write(List.of(route))).get(0);

		assertThat(written.get("predicates")).isEqualTo(List.of("Many=a,c,k"));
	}

	@Test
	void shouldWriteNamedArgumentsInTheLongForm() {
		RouteDefinition route = route("alpha", "http://alpha.example.com");
		route.setPredicates(List.of(predicate("Cookie", Map.of("name", "session"))));

		Map<String, Object> written = routesOf(RouteDefinitionYaml.write(List.of(route))).get(0);

		assertThat(written.get("predicates"))
			.isEqualTo(List.of(Map.of("name", "Cookie", "args", Map.of("name", "session"))));
	}

	@Test
	void shouldWriteAValueHoldingACommaInTheLongForm() {
		// The shortcut splits its value on commas: written short, this one argument would
		// come back as two.
		RouteDefinition route = route("alpha", "http://alpha.example.com");
		route.setPredicates(List.of(predicate("Header", Map.of("_genkey_0", "X-Tenant,X-Other"))));

		Map<String, Object> written = routesOf(RouteDefinitionYaml.write(List.of(route))).get(0);

		assertThat(written.get("predicates"))
			.isEqualTo(List.of(Map.of("name", "Header", "args", Map.of("_genkey_0", "X-Tenant,X-Other"))));
	}

	@Test
	void shouldWriteAnElementWithoutArgumentsAsItsNameAlone() {
		RouteDefinition route = route("alpha", "http://alpha.example.com");
		route.setFilters(List.of(filter("Dedupe", Map.of())));

		Map<String, Object> written = routesOf(RouteDefinitionYaml.write(List.of(route))).get(0);

		assertThat(written.get("filters")).isEqualTo(List.of("Dedupe"));
	}

	@Test
	void shouldWriteTheOrderOnlyWhenTheRouteDeclaredOne() {
		RouteDefinition ordered = route("beta", "http://beta.example.com");
		ordered.setOrder(-5);

		assertThat(routesOf(RouteDefinitionYaml.write(List.of(route("alpha", "http://alpha.example.com")))).get(0))
			.doesNotContainKey("order");
		assertThat(routesOf(RouteDefinitionYaml.write(List.of(ordered))).get(0)).containsEntry("order", -5);
	}

	@Test
	void shouldKeepTheMetadataOfARouteThatCarriesSome() {
		RouteDefinition route = route("alpha", "http://alpha.example.com");
		route.setMetadata(Map.of("public", true));

		assertThat(routesOf(RouteDefinitionYaml.write(List.of(route))).get(0)).containsEntry("metadata",
				Map.of("public", true));
	}

	@Test
	void shouldWriteAnEmptySelectionAsAnEmptyRouteList() {
		assertThat(routesOf(RouteDefinitionYaml.write(List.of()))).isEmpty();
	}

	private static RouteDefinition route(String id, String uri) {
		RouteDefinition definition = new RouteDefinition();
		definition.setId(id);
		definition.setUri(URI.create(uri));
		return definition;
	}

	private static PredicateDefinition predicate(String name, Map<String, String> args) {
		PredicateDefinition predicate = new PredicateDefinition();
		predicate.setName(name);
		predicate.setArgs(args);
		return predicate;
	}

	private static FilterDefinition filter(String name, Map<String, String> args) {
		FilterDefinition filter = new FilterDefinition();
		filter.setName(name);
		filter.setArgs(args);
		return filter;
	}

	private static Map<String, Object> read(String yaml) {
		return new Yaml().load(yaml);
	}

	/** The route list as the gateway would bind it, read back out of the document. */
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> routesOf(String yaml) {
		Map<String, Object> document = read(yaml);
		for (String segment : List.of("spring", "cloud", "gateway", "server", "webflux")) {
			document = (Map<String, Object>) document.get(segment);
		}
		return (List<Map<String, Object>>) document.get("routes");
	}

}
