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

package ch.nexsol.gateway.ui.openapi;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the OpenAPI view.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.ui.openapi")
public class OpenapiViewProperties {

	/**
	 * The vendor extensions to show in the OpenAPI view, keyed by extension name, each
	 * with the label it reads under, in the order they are declared.
	 * <p>
	 * The renderer displays the extensions it knows about and matches the others by their
	 * exact name, so an extension left out of this mapping is not shown. With
	 * {@code x-roles: Required roles}, an operation carrying {@code x-roles} reads as
	 * {@code Required roles}, its value untouched and its contract unchanged.
	 */
	private final Map<String, String> extensions = new LinkedHashMap<>();

	/**
	 * Whether the OpenAPI view offers to call the documented operations.
	 * <p>
	 * Off takes the "Test Request" button off every operation, and with it the
	 * authentication panel: the renderer gates that panel on the button. The routes are
	 * reached the same way with the button gone.
	 */
	private boolean tryIt = true;

	/**
	 * Returns the extensions to show, keyed by extension name.
	 * @return the extensions to show, keyed by extension name
	 */
	public Map<String, String> getExtensions() {
		return this.extensions;
	}

	/**
	 * Returns whether the OpenAPI view offers to call the documented operations.
	 * @return whether the view offers to call the documented operations
	 */
	public boolean isTryIt() {
		return this.tryIt;
	}

	/**
	 * Sets whether the OpenAPI view offers to call the documented operations.
	 * @param tryIt whether the view offers to call the documented operations
	 */
	public void setTryIt(boolean tryIt) {
		this.tryIt = tryIt;
	}

}
