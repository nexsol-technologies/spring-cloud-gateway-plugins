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

package ch.nexsol.gateway.routes.files;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the file-based route definition locator.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.routes.files")
public class RoutesFilesProperties {

	/**
	 * Whether the file-based route definition locator is enabled.
	 */
	private boolean enabled;

	/**
	 * Spring resource patterns pointing at the JSON/YAML route files to load, for example
	 * {@code classpath:gateway/routes/*.yaml} or {@code file:./config/routes/*.json}.
	 */
	private List<String> locations = new ArrayList<>();

	/**
	 * Whether to watch the filesystem directories of the resolved locations and reload
	 * the routes when a file changes. Only {@code file:} locations can be watched.
	 */
	private boolean watch;

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public List<String> getLocations() {
		return this.locations;
	}

	public void setLocations(List<String> locations) {
		this.locations = locations;
	}

	public boolean isWatch() {
		return this.watch;
	}

	public void setWatch(boolean watch) {
		this.watch = watch;
	}

}
