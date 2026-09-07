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

package ch.nexsol.gateway.ui.insights;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

/**
 * Configuration properties for the introspection views of the console.
 * <p>
 * The views read the Actuator endpoints of the gateway over HTTP rather than through the
 * endpoint beans, so an instance other than the one answering can be read the same way.
 * What they show is therefore exactly what Actuator serves: a value Actuator masks
 * reaches this console masked, and an endpoint it does not expose is an endpoint the view
 * reports as unavailable.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.ui.insights")
public class InsightsProperties {

	/**
	 * Whether the introspection views are served.
	 */
	private boolean enabled = true;

	/**
	 * Base path the Actuator endpoints are exposed under. Empty, the management
	 * configuration of this instance decides, which is what makes a separate management
	 * port or a moved base path work without saying so twice. Set, it applies to every
	 * instance, including the ones read over the network.
	 */
	private String basePath = "";

	/**
	 * How long an endpoint is given to answer before the view reports it unreachable.
	 */
	private Duration timeout = Duration.ofSeconds(5);

	/**
	 * Whether the loggers view may change a level at all. The master switch, above the
	 * role: turned off, no principal changes a level however privileged.
	 */
	private boolean loggersWritable = true;

	/**
	 * The role a principal must hold to change a logging level, without its {@code ROLE_}
	 * prefix. Left empty, any authenticated principal may; an anonymous one never may,
	 * whatever this says.
	 */
	private String loggersRole = "ADMIN";

	/**
	 * How large a payload one endpoint may return. The condition report and the bean
	 * report of a gateway running every plugin both run past a megabyte, where the
	 * default of the web client is 256 KB.
	 */
	private DataSize maxPayload = DataSize.ofMegabytes(8);

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getBasePath() {
		return this.basePath;
	}

	public void setBasePath(String basePath) {
		this.basePath = basePath;
	}

	/**
	 * The base path to use: the configured one when set, the given fallback otherwise.
	 * @param fallback the path the management configuration resolved to
	 * @return the base path
	 */
	public String basePathOr(String fallback) {
		return StringUtils.hasText(this.basePath) ? this.basePath : fallback;
	}

	public Duration getTimeout() {
		return this.timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public boolean isLoggersWritable() {
		return this.loggersWritable;
	}

	public void setLoggersWritable(boolean loggersWritable) {
		this.loggersWritable = loggersWritable;
	}

	public String getLoggersRole() {
		return this.loggersRole;
	}

	public void setLoggersRole(String loggersRole) {
		this.loggersRole = loggersRole;
	}

	public DataSize getMaxPayload() {
		return this.maxPayload;
	}

	public void setMaxPayload(DataSize maxPayload) {
		this.maxPayload = maxPayload;
	}

	/**
	 * The payload ceiling as the web client wants it, which is an {@code int} where a
	 * {@link DataSize} is a {@code long}. Clamped rather than cast: a size configured
	 * above two gigabytes would otherwise wrap round to a negative ceiling and refuse
	 * every payload.
	 * @return the ceiling in bytes, never negative
	 */
	public int maxPayloadBytes() {
		return (int) Math.min(this.maxPayload.toBytes(), Integer.MAX_VALUE);
	}

}
