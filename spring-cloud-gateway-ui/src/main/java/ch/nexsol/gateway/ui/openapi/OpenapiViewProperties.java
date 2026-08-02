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
	 * The vendor extensions of the contracts to show in the OpenAPI view, keyed by
	 * extension name, each with the label it reads under, in the order they are declared.
	 * <p>
	 * Declaring an extension is what makes it visible: the renderer displays the handful
	 * it knows about and matches the others by their exact name, so an extension left out
	 * of this mapping is not shown. Declaring {@code x-roles: Required roles} has an
	 * operation carrying {@code x-roles} read as {@code Required roles}, the value
	 * untouched, without changing the contract itself.
	 */
	private final Map<String, String> extensions = new LinkedHashMap<>();

	/**
	 * Returns the extensions to show, keyed by extension name.
	 * @return the labels, keyed by extension name
	 */
	public Map<String, String> getExtensions() {
		return this.extensions;
	}

}
