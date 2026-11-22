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

package ch.nexsol.gateway.routes.openapi;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.nexsol.gateway.routes.openapi.RoutesOpenapiProperties.Source;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the time a contract is kept. A poll that finds the same sources has no reason to
 * fetch and parse every contract again, so a contract is kept for a while; whoever asks
 * for a refresh, though, expects the contracts themselves to be read again.
 */
class OpenApiContractTtlTests {

	private static final Duration TTL = Duration.ofMinutes(10);

	private final List<String> reads = new CopyOnWriteArrayList<>();

	private final MovingClock clock = new MovingClock();

	private boolean failing;

	private final OpenApiSpecLoader countingLoader = (url) -> {
		this.reads.add(url);
		if (this.failing) {
			throw new OpenApiRouteException("the contract cannot be read");
		}
		return parse();
	};

	private final List<Source> declared = new ArrayList<>(List.of(source("petstore"), source("orders")));

	@Test
	void keepsAContractForAsLongAsItIsToldTo() {
		OpenApiRouteDefinitionLoader loader = loader(TTL);

		assertThat(loader.load()).hasSize(2);
		this.clock.advance(TTL.minusSeconds(1));
		assertThat(loader.load()).hasSize(2);

		assertThat(this.reads).containsExactlyInAnyOrder("stub://petstore", "stub://orders");
	}

	@Test
	void readsAContractAgainOnceItsTimeIsUp() {
		OpenApiRouteDefinitionLoader loader = loader(TTL);

		loader.load();
		this.clock.advance(TTL.plusSeconds(1));
		loader.load();

		assertThat(this.reads).hasSize(4);
	}

	@Test
	void readsEveryContractAgainOnceToldToForgetThem() {
		OpenApiRouteDefinitionLoader loader = loader(TTL);

		loader.load();
		loader.forget();
		loader.load();

		assertThat(this.reads).hasSize(4);
	}

	@Test
	void doesNotKeepAContractItCouldNotRead() {
		OpenApiRouteDefinitionLoader loader = loader(TTL);
		this.failing = true;
		assertThat(loader.load()).isEmpty();

		this.failing = false;

		assertThat(loader.load()).hasSize(2);
		assertThat(this.reads).hasSize(4);
	}

	@Test
	void readsTheContractOfASourceDeclaredSinceTheLastLoad() {
		OpenApiRouteDefinitionLoader loader = loader(TTL);
		loader.load();

		this.declared.add(source("billing"));

		assertThat(loader.load()).hasSize(3);
		assertThat(this.reads).containsExactlyInAnyOrder("stub://petstore", "stub://orders", "stub://billing");
	}

	@Test
	void readsEveryContractOnEveryLoadWhenNoTimeIsSet() {
		OpenApiRouteDefinitionLoader loader = loader(null);

		loader.load();
		loader.load();

		assertThat(this.reads).hasSize(4);
	}

	@Test
	void readsEveryContractOnEveryLoadWhenTheTimeIsZero() {
		OpenApiRouteDefinitionLoader loader = loader(Duration.ZERO);

		loader.load();
		loader.load();

		assertThat(this.reads).hasSize(4);
	}

	@Test
	void aPollKeepsTheContractsAndAnExplicitReloadReadsThemAgain() {
		ApplicationEventPublisher publisher = (event) -> {
		};
		OpenApiRouteDefinitionLocator locator = new OpenApiRouteDefinitionLocator(loader(TTL), publisher);

		StepVerifier.create(locator.refresh()).verifyComplete();
		StepVerifier.create(locator.refresh()).verifyComplete();
		assertThat(this.reads).hasSize(2);

		StepVerifier.create(locator.reload()).verifyComplete();
		assertThat(this.reads).hasSize(4);
	}

	private OpenApiRouteDefinitionLoader loader(Duration ttl) {
		RoutesOpenapiProperties properties = new RoutesOpenapiProperties() {
			@Override
			public List<Source> getSources() {
				return List.copyOf(OpenApiContractTtlTests.this.declared);
			}
		};
		OpenapiSourcesLoader sources = new OpenapiSourcesLoader(properties, new PathMatchingResourcePatternResolver());
		return new OpenApiRouteDefinitionLoader(this.countingLoader, new OpenApiRouteDefinitionMapper(), sources, ttl,
				this.clock);
	}

	private static Source source(String id) {
		Source source = new Source();
		source.setId(id);
		source.setUri(URI.create("https://" + id + ".example.org"));
		source.setSpecUrl("stub://" + id);
		source.setMode(RouteGenerationMode.AGGREGATED);
		return source;
	}

	private static OpenAPI parse() {
		try {
			String content = new ClassPathResource("openapi/petstore.yaml").getContentAsString(StandardCharsets.UTF_8);
			ParseOptions options = new ParseOptions();
			options.setResolve(true);
			return new OpenAPIV3Parser().readContents(content, null, options).getOpenAPI();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static final class MovingClock extends Clock {

		private Instant now = Instant.parse("2026-01-01T00:00:00Z");

		void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public Instant instant() {
			return this.now;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

}
