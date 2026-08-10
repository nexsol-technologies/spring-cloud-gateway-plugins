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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configuration properties for the OpenAPI validation filter, bound under
 * {@code spring.cloud.gateway.server.webflux.openapi-validation}. Each direction is
 * configured on its own, so a request can be enforced while the responses of the same
 * route are only reported on.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.openapi-validation")
public class OpenapiValidationProperties {

	/**
	 * How the requests are validated. Enforced by default: rejecting a request that
	 * breaks the contract is what shields the downstream service.
	 */
	private Direction request = new Direction();

	/**
	 * How the responses are validated. Reported on by default: a downstream service
	 * answering outside its own contract is a defect to be measured, but turning it into
	 * a gateway error would break the clients that cope with it today.
	 */
	private Direction response = reporting();

	/**
	 * The response direction starts in {@link ValidationMode#REPORT} rather than the
	 * {@link ValidationMode#ENFORCE} the field default gives, so the two directions
	 * differ out of the box.
	 */
	private static Direction reporting() {
		Direction direction = new Direction();
		direction.setMode(ValidationMode.REPORT);
		return direction;
	}

	/**
	 * @return the request direction settings
	 */
	public Direction getRequest() {
		return this.request;
	}

	/**
	 * @param request the request direction settings
	 */
	public void setRequest(Direction request) {
		this.request = request;
	}

	/**
	 * @return the response direction settings
	 */
	public Direction getResponse() {
		return this.response;
	}

	/**
	 * @param response the response direction settings
	 */
	public void setResponse(Direction response) {
		this.response = response;
	}

	/**
	 * Settings of one direction of the exchange.
	 * <p>
	 * Deliberately a plain java bean with no parameterised constructor: a single
	 * constructor taking the mode would make Spring Boot, and its configuration metadata
	 * processor, read this as a value object bound through that constructor &mdash; and
	 * every other setting here would silently stop being bindable and stop being offered
	 * for completion.
	 */
	public static class Direction {

		/**
		 * What to do with a message that does not honour the contract.
		 */
		private ValidationMode mode = ValidationMode.ENFORCE;

		/**
		 * Whether the body is validated against the schema of the contract. Validating a
		 * body means buffering it whole, so turning this off keeps the exchange streamed
		 * while still checking everything that is readable from the headers.
		 */
		private boolean validateBody = true;

		/**
		 * Bodies larger than this are forwarded without being validated rather than
		 * buffered, so a large payload cannot exhaust the memory of the gateway. The skip
		 * is logged.
		 */
		private DataSize maxBodySize = DataSize.ofMegabytes(1);

		/**
		 * @return what to do with a message that breaks the contract
		 */
		public ValidationMode getMode() {
			return this.mode;
		}

		/**
		 * @param mode what to do with a message that breaks the contract
		 */
		public void setMode(ValidationMode mode) {
			this.mode = mode;
		}

		/**
		 * @return whether the body is validated
		 */
		public boolean isValidateBody() {
			return this.validateBody;
		}

		/**
		 * @param validateBody whether the body is validated
		 */
		public void setValidateBody(boolean validateBody) {
			this.validateBody = validateBody;
		}

		/**
		 * @return the largest body that is buffered to be validated
		 */
		public DataSize getMaxBodySize() {
			return this.maxBodySize;
		}

		/**
		 * @param maxBodySize the largest body that is buffered to be validated
		 */
		public void setMaxBodySize(DataSize maxBodySize) {
			this.maxBodySize = maxBodySize;
		}

		/**
		 * Returns whether this direction is validated at all.
		 * @return {@code true} unless the mode is {@link ValidationMode#OFF}
		 */
		public boolean isActive() {
			return this.mode != ValidationMode.OFF;
		}

		/**
		 * Returns whether a message breaking the contract is rejected.
		 * @return {@code true} when the mode is {@link ValidationMode#ENFORCE}
		 */
		public boolean isEnforced() {
			return this.mode == ValidationMode.ENFORCE;
		}

	}

}
