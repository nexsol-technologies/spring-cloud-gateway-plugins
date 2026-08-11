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

package ch.nexsol.gateway.sample;

import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import ch.nexsol.gateway.ui.nav.GatewayUiMenu;
import ch.nexsol.gateway.validation.OpenapiValidationProperties;
import ch.nexsol.gateway.validation.ValidationMode;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 'plugins-off' profile is the claim that every plugin here is configuration rather
 * than code, and this is what makes it a claim rather than a comment: the same classpath,
 * and a gateway that routes traffic and does nothing else.
 * <p>
 * The two plugins with no switch of their own go through Boot's
 * {@code spring.autoconfigure.exclude}, which is a different thing and shows: the console
 * is not disabled, it is absent.
 */
@SpringBootTest
@ActiveProfiles("plugins-off")
@AutoConfigureWebTestClient
class PluginsOffProfileTests {

	@Autowired
	ApplicationContext context;

	@Autowired
	WebTestClient webTestClient;

	@Autowired
	RouteDefinitionLocator routeDefinitionLocator;

	@Autowired
	OpenapiValidationProperties validation;

	@Test
	void shouldLeaveNoPluginWiredButTheGatewayItself() {
		assertThat(this.context.getBeanNamesForType(RouteMetricsSource.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(AuditEventPublisher.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(GatewayUiMenu.class)).isEmpty();
		// Quoted in the YAML, or OFF reads as the boolean false and never binds.
		assertThat(this.validation.getRequest().getMode()).isEqualTo(ValidationMode.OFF);
		assertThat(this.validation.getResponse().getMode()).isEqualTo(ValidationMode.OFF);
	}

	/**
	 * The console is excluded rather than disabled, so its paths are not answered at all
	 * &mdash; and the rule this application keeps over the database routes page must not
	 * send a visitor to a login page that is no longer served.
	 */
	@Test
	void shouldServeNothingUnderUi() {
		this.webTestClient.get().uri("/ui").exchange().expectStatus().isNotFound();
		this.webTestClient.get().uri("/ui/login").exchange().expectStatus().isNotFound();
	}

	/**
	 * The routes of {@code application.yml} are the gateway's own, and the filters they
	 * name come from the plugins: a filter factory is only ever applied by a route asking
	 * for it, so there is nothing to turn off and the route table still builds.
	 */
	@Test
	void shouldKeepTheRoutesTheGatewayItselfDeclares() {
		StepVerifier.create(this.routeDefinitionLocator.getRouteDefinitions().map(RouteDefinition::getId).collectList())
			.assertNext((ids) -> assertThat(ids).contains("test-authorization", "test-authorization-token-OK")
				.doesNotContain("files_httpbin_route"))
			.verifyComplete();
	}

}
