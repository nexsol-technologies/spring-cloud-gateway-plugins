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

package ch.nexsol.gateway.sample.passivescan;

import java.time.Duration;

import ch.nexsol.gateway.pentest.core.store.FindingStore;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the analyser raises findings from the traffic the gateway answers. The filter
 * runs on every exchange, so even a request no route matched &mdash; served by the
 * gateway as a 404 without the usual security headers &mdash; produces a finding.
 * Inspection is asynchronous, hence the await.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.cloud.gateway.server.webflux.pentest.passive.routed-only=false")
@AutoConfigureWebTestClient
class PassiveScanGatewayApplicationTests {

	@Autowired
	WebTestClient webTestClient;

	@Autowired
	FindingStore findingStore;

	@Test
	void shouldRaiseFindingsFromAnsweredTraffic() {
		this.webTestClient.get().uri("/nothing-here").exchange();

		Awaitility.await()
			.atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(this.findingStore.recent()).isNotEmpty());
	}

}
