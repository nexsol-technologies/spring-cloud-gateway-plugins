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

	private final Map<String, String> extensions = new LinkedHashMap<>();

	/**
	 * Labels the vendor extensions of the contracts are shown under, keyed by extension
	 * name, in the order they are declared.
	 * <p>
	 * Naming an extension here only changes how it reads: an extension left undeclared is
	 * still shown, under its own name. Declaring {@code x-roles: Required roles} turns
	 * the {@code **x-roles**} line of an operation into {@code **Required roles**},
	 * without touching the contract itself.
	 * @return the labels, keyed by extension name
	 */
	public Map<String, String> getExtensions() {
		return this.extensions;
	}

}
