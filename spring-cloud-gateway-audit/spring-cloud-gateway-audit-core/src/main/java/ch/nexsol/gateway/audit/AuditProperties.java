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

package ch.nexsol.gateway.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration properties for the auditing plugin, bound under
 * {@code spring.cloud.gateway.server.webflux.audit}. Each logical group of attributes can
 * be toggled independently through {@link Groups}.
 */
public class AuditProperties {

	/**
	 * Whether the auditing machinery is active. When {@code false} no audit filter is
	 * registered.
	 */
	private boolean enabled = true;

	/**
	 * Selected audit provider. Read by the provider modules (for example {@code kafka},
	 * {@code redis} or {@code r2dbc}) to activate their publisher. When unset, the
	 * default logging/{@code ApplicationEvent} publisher is used.
	 */
	private String provider;

	/**
	 * Metadata added to every audit event, audited under the {@code metadata.} prefix.
	 * Use it to stamp the events with what identifies this gateway rather than the
	 * exchange: the environment, the datacenter, the instance.
	 */
	private Map<String, String> metadata = new LinkedHashMap<>();

	private Groups groups = new Groups();

	private WebFilter webFilter = new WebFilter();

	/**
	 * @return whether auditing is enabled
	 */
	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * @param enabled whether auditing is enabled
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * @return the selected audit provider
	 */
	public String getProvider() {
		return this.provider;
	}

	/**
	 * @param provider the selected audit provider
	 */
	public void setProvider(String provider) {
		this.provider = provider;
	}

	/**
	 * @return the metadata added to every audit event
	 */
	public Map<String, String> getMetadata() {
		return this.metadata;
	}

	/**
	 * @param metadata the metadata added to every audit event
	 */
	public void setMetadata(Map<String, String> metadata) {
		this.metadata = metadata;
	}

	/**
	 * @return the per-group feature flags
	 */
	public Groups getGroups() {
		return this.groups;
	}

	/**
	 * @param groups the per-group feature flags
	 */
	public void setGroups(Groups groups) {
		this.groups = groups;
	}

	/**
	 * @return the global web filter configuration
	 */
	public WebFilter getWebFilter() {
		return this.webFilter;
	}

	/**
	 * @param webFilter the global web filter configuration
	 */
	public void setWebFilter(WebFilter webFilter) {
		this.webFilter = webFilter;
	}

	/**
	 * Feature flags enabling or disabling each logical group of audited attributes.
	 */
	public static class Groups {

		/**
		 * Collect the {@code jwt.*} attributes.
		 */
		private boolean jwt = true;

		/**
		 * Collect the {@code request.*} attributes.
		 */
		private boolean request = true;

		/**
		 * Collect the {@code response.*} attributes.
		 */
		private boolean response = true;

		/**
		 * Collect the {@code trace.*} attributes.
		 */
		private boolean trace = true;

		/**
		 * Collect the {@code route.*} attributes: the id of the route that handled the
		 * exchange and the metadata declared on it.
		 */
		private boolean route = true;

		/**
		 * @return whether the JWT group is collected
		 */
		public boolean isJwt() {
			return this.jwt;
		}

		/**
		 * @param jwt whether the JWT group is collected
		 */
		public void setJwt(boolean jwt) {
			this.jwt = jwt;
		}

		/**
		 * @return whether the request group is collected
		 */
		public boolean isRequest() {
			return this.request;
		}

		/**
		 * @param request whether the request group is collected
		 */
		public void setRequest(boolean request) {
			this.request = request;
		}

		/**
		 * @return whether the response group is collected
		 */
		public boolean isResponse() {
			return this.response;
		}

		/**
		 * @param response whether the response group is collected
		 */
		public void setResponse(boolean response) {
			this.response = response;
		}

		/**
		 * @return whether the trace group is collected
		 */
		public boolean isTrace() {
			return this.trace;
		}

		/**
		 * @param trace whether the trace group is collected
		 */
		public void setTrace(boolean trace) {
			this.trace = trace;
		}

		/**
		 * @return whether the route group is collected
		 */
		public boolean isRoute() {
			return this.route;
		}

		/**
		 * @param route whether the route group is collected
		 */
		public void setRoute(boolean route) {
			this.route = route;
		}

	}

	/**
	 * Configuration of the global {@code WebFilter} that audits every request.
	 */
	public static class WebFilter {

		/**
		 * Whether the global auditing web filter is registered. Disabled by default; the
		 * per-route {@code Audit} gateway filter is always available.
		 */
		private boolean enabled = false;

		/**
		 * @return whether the global web filter is enabled
		 */
		public boolean isEnabled() {
			return this.enabled;
		}

		/**
		 * @param enabled whether the global web filter is enabled
		 */
		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

	}

}
