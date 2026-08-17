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

package ch.nexsol.gateway.filter;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration of {@link IdentityPropagationFilter}, bound under
 * {@code spring.cloud.gateway.server.webflux.identity-propagation}.
 */
public class IdentityPropagationProperties {

	/**
	 * Whether the identity headers are propagated. Off by default: the filter rewrites
	 * headers on every routed request, which is not something a gateway should start
	 * doing because a jar landed on its classpath.
	 */
	private boolean enabled;

	/**
	 * Client ids whose {@code origin} headers are believed. A request presenting one of
	 * these keeps the origin identity it carries; every other request has it replaced by
	 * the identity of its own token.
	 * <p>
	 * Empty by default, which means no incoming origin header is ever believed. Matching
	 * is exact: a client id is case sensitive, and lower casing it here would make two
	 * distinct clients one.
	 */
	private List<String> internalClients = new ArrayList<>();

	/**
	 * Names of the headers carrying the identity of the current caller.
	 */
	private Headers current = new Headers("x-issuerid", "x-clientid", "x-userid");

	/**
	 * Names of the headers carrying the identity that started the call chain.
	 */
	private Headers origin = new Headers("x-origin-issuerid", "x-origin-clientid", "x-origin-userid");

	/**
	 * @return whether the identity headers are propagated
	 */
	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * @param enabled whether the identity headers are propagated
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * @return the client ids whose origin headers are believed
	 */
	public List<String> getInternalClients() {
		return this.internalClients;
	}

	/**
	 * @param internalClients the client ids whose origin headers are believed
	 */
	public void setInternalClients(List<String> internalClients) {
		this.internalClients = internalClients;
	}

	/**
	 * @return the headers carrying the current identity
	 */
	public Headers getCurrent() {
		return this.current;
	}

	/**
	 * @param current the headers carrying the current identity
	 */
	public void setCurrent(Headers current) {
		this.current = current;
	}

	/**
	 * @return the headers carrying the origin identity
	 */
	public Headers getOrigin() {
		return this.origin;
	}

	/**
	 * @param origin the headers carrying the origin identity
	 */
	public void setOrigin(Headers origin) {
		this.origin = origin;
	}

	/**
	 * The three header names an identity is written to.
	 */
	public static class Headers {

		/**
		 * Header carrying the issuer of the token.
		 */
		private String issuer;

		/**
		 * Header carrying the client the token was issued to.
		 */
		private String client;

		/**
		 * Header carrying the user the token stands for.
		 */
		private String user;

		/**
		 * Creates the header names.
		 * @param issuer the issuer header name
		 * @param client the client header name
		 * @param user the user header name
		 */
		public Headers(String issuer, String client, String user) {
			this.issuer = issuer;
			this.client = client;
			this.user = user;
		}

		/**
		 * @return the issuer header name
		 */
		public String getIssuer() {
			return this.issuer;
		}

		/**
		 * @param issuer the issuer header name
		 */
		public void setIssuer(String issuer) {
			this.issuer = issuer;
		}

		/**
		 * @return the client header name
		 */
		public String getClient() {
			return this.client;
		}

		/**
		 * @param client the client header name
		 */
		public void setClient(String client) {
			this.client = client;
		}

		/**
		 * @return the user header name
		 */
		public String getUser() {
			return this.user;
		}

		/**
		 * @param user the user header name
		 */
		public void setUser(String user) {
			this.user = user;
		}

	}

}
