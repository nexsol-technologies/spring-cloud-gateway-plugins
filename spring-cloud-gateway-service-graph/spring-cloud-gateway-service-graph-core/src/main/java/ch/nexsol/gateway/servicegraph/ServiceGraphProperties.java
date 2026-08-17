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

package ch.nexsol.gateway.servicegraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the service graph plugin, bound under
 * {@code spring.cloud.gateway.server.webflux.service-graph}.
 */
public class ServiceGraphProperties {

	/**
	 * Whether the service graph plugin is active. When {@code false} nothing is counted
	 * and no source is registered.
	 */
	private boolean enabled = true;

	/**
	 * Selected graph source. Read by the provider modules to activate their source. When
	 * unset, the graph of the running instance is reported.
	 */
	private String provider;

	/**
	 * Identifier of this instance, shown next to the graph it produced. Defaults to the
	 * host name, which is the pod name on Kubernetes.
	 */
	private String instanceId;

	/**
	 * Configuration of the caller side of the graph.
	 */
	private Caller caller = new Caller();

	/**
	 * @return whether the service graph plugin is enabled
	 */
	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * @param enabled whether the service graph plugin is enabled
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * @return the selected graph source
	 */
	public String getProvider() {
		return this.provider;
	}

	/**
	 * @param provider the selected graph source
	 */
	public void setProvider(String provider) {
		this.provider = provider;
	}

	/**
	 * @return the configured instance identifier
	 */
	public String getInstanceId() {
		return this.instanceId;
	}

	/**
	 * @param instanceId the instance identifier
	 */
	public void setInstanceId(String instanceId) {
		this.instanceId = instanceId;
	}

	/**
	 * @return the caller side configuration
	 */
	public Caller getCaller() {
		return this.caller;
	}

	/**
	 * @param caller the caller side configuration
	 */
	public void setCaller(Caller caller) {
		this.caller = caller;
	}

	/**
	 * How the caller of a request is named, and how many distinct names are allowed.
	 */
	public static class Caller {

		/**
		 * JWT claims read in order, the first one carrying a value naming the caller.
		 * These are the claims holding the client the token was issued to, not the user
		 * behind it: a caller becomes a tag on a counter, and there are few clients where
		 * there can be any number of users.
		 */
		private List<String> claims = new ArrayList<>(List.of("azp", "client_id"));

		/**
		 * Request header naming the caller when no claim did, for a gateway whose callers
		 * do not carry a token. Unset by default, and never trusted for anything but the
		 * graph: whoever calls the gateway chooses what it contains.
		 */
		private String header;

		/**
		 * Whether the header is read before the claims rather than after them.
		 * <p>
		 * Needed where a service calling another one through the gateway relays the token
		 * of the end user instead of presenting its own: the claims then still name the
		 * client that started the chain, so an edge read from them would join two
		 * endpoints that never talked to each other. The header, set by the calling
		 * service, is the only thing naming the real caller there.
		 * <p>
		 * Turning this on makes the graph depend on a value the caller chooses, so the
		 * header has to be stripped from the traffic entering the gateway from outside
		 * and set again from the token &mdash; otherwise anyone can draw an edge.
		 */
		private boolean headerFirst;

		/**
		 * Number of distinct callers the graph names. Every caller beyond it is counted
		 * under a single {@code _other_} node.
		 * <p>
		 * This is what keeps the meter registry, and whatever backend scrapes it, from
		 * growing one time series per caller ever seen. Raise it only for a gateway whose
		 * callers are known to be few and named.
		 */
		private int max = 100;

		/**
		 * @return the JWT claims naming the caller
		 */
		public List<String> getClaims() {
			return this.claims;
		}

		/**
		 * @param claims the JWT claims naming the caller
		 */
		public void setClaims(List<String> claims) {
			this.claims = claims;
		}

		/**
		 * @return the header naming the caller, {@code null} when unset
		 */
		public String getHeader() {
			return this.header;
		}

		/**
		 * @param header the header naming the caller
		 */
		public void setHeader(String header) {
			this.header = header;
		}

		/**
		 * @return whether the header is read before the claims
		 */
		public boolean isHeaderFirst() {
			return this.headerFirst;
		}

		/**
		 * @param headerFirst whether the header is read before the claims
		 */
		public void setHeaderFirst(boolean headerFirst) {
			this.headerFirst = headerFirst;
		}

		/**
		 * @return the number of distinct callers the graph names
		 */
		public int getMax() {
			return this.max;
		}

		/**
		 * @param max the number of distinct callers the graph names
		 */
		public void setMax(int max) {
			this.max = max;
		}

	}

}
