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

package ch.nexsol.gateway.validation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * Counts what the validation filter sees, as plain Micrometer meters: the host
 * application exports them to Prometheus, OpenTelemetry or anything else it already
 * configures a registry for.
 * <p>
 * Every tag is drawn from a bounded set &mdash; the operations of a contract, the routes
 * of the gateway, a fixed outcome &mdash; so the number of series stays proportional to
 * the size of the configuration. The violations themselves are never tagged: they belong
 * in the audit trail and the logs, not in a metric whose cardinality they would blow up.
 * <p>
 * A gateway with no {@link MeterRegistry} bean gets a no-op instance, so the filter works
 * the same without the actuator on the classpath.
 */
public class ValidationMetrics {

	/**
	 * Counter of the validated messages, tagged by direction, route, operation and
	 * outcome.
	 */
	public static final String VALIDATIONS = "gateway.openapi.validations";

	/**
	 * Counter of the bodies that were forwarded without being validated, tagged by the
	 * reason.
	 */
	public static final String BODIES_SKIPPED = "gateway.openapi.validation.bodies.skipped";

	/**
	 * Counter of the exchanges forwarded without validation because their contract could
	 * not be read.
	 */
	public static final String CONTRACTS_UNAVAILABLE = "gateway.openapi.validation.contracts.unavailable";

	private final MeterRegistry registry;

	/**
	 * Creates the meters.
	 * @param registry the registry the meters are published to, or {@code null} to record
	 * nothing
	 */
	public ValidationMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Counts one validated message.
	 * @param direction {@code request} or {@code response}
	 * @param routeId the route that handled the exchange
	 * @param operation the contract operation, as {@code METHOD /template}
	 * @param mode the mode the direction was validated in
	 * @param valid whether the message honoured the contract
	 */
	public void validated(String direction, String routeId, String operation, ValidationMode mode, boolean valid) {
		if (this.registry == null) {
			return;
		}
		this.registry
			.counter(VALIDATIONS, Tags.of("direction", direction, "route", routeId, "operation", operation, "mode",
					mode.name(), "outcome", valid ? "valid" : "invalid"))
			.increment();
	}

	/**
	 * Counts one body that was forwarded without being validated.
	 * @param direction {@code request} or {@code response}
	 * @param routeId the route that handled the exchange
	 * @param reason why the body was not validated
	 */
	public void bodySkipped(String direction, String routeId, SkipReason reason) {
		if (this.registry == null) {
			return;
		}
		this.registry
			.counter(BODIES_SKIPPED,
					Tags.of("direction", direction, "route", routeId, "reason", reason.name().toLowerCase()))
			.increment();
	}

	/**
	 * Counts one exchange forwarded because its contract could not be read.
	 * @param location the contract location
	 */
	public void contractUnavailable(String location) {
		if (this.registry == null) {
			return;
		}
		this.registry.counter(CONTRACTS_UNAVAILABLE, Tags.of("contract", location)).increment();
	}

	/**
	 * Why a body was forwarded without being held against its schema.
	 */
	public enum SkipReason {

		/**
		 * The media type carries nothing a JSON schema applies to: an upload, a binary
		 * stream, a form submission.
		 */
		NOT_JSON,

		/**
		 * The body is compressed, and decompressing it to validate it is out of scope.
		 */
		ENCODED,

		/**
		 * The body is larger than the configured maximum.
		 */
		TOO_LARGE,

		/**
		 * The length of the body is not announced, so buffering it could not be bounded.
		 */
		UNKNOWN_LENGTH

	}

}
