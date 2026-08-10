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

/**
 * What the filter does with a message that does not honour the contract, configured
 * independently for the request and the response direction.
 */
public enum ValidationMode {

	/**
	 * Do not validate this direction at all. No body is buffered, so the streaming
	 * behaviour of the gateway is left untouched.
	 */
	OFF,

	/**
	 * Validate and record the outcome, but forward the message unchanged. Use it to
	 * measure how far an existing traffic is from its contract before enforcing anything.
	 */
	REPORT,

	/**
	 * Validate and reject a message that breaks the contract: {@code 400 Bad Request} for
	 * a request, {@code 502 Bad Gateway} for a response.
	 */
	ENFORCE

}
