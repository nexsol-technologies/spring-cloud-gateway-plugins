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

package ch.nexsol.gateway.commons.security;

import java.util.List;

/**
 * The exact paths a plugin serves, declared so that the console can govern them.
 * <p>
 * A plugin knows the paths it answers; only the console knows how to authenticate a
 * visitor &mdash; its login page, its identity providers, its Bearer tokens and its
 * roles. This contract carries the first towards the second, without a plugin having to
 * depend on the console: a gateway assembled without it simply has nobody collecting the
 * contributions, and each plugin keeps whatever chain it declares on its own.
 * <p>
 * Paths are matched exactly, or with a pattern that stays inside the namespace the plugin
 * owns. A blanket {@code /**} would also open the gateway routes an application declared
 * under the same prefix, which the plugin does not serve and did not intend to expose.
 * <p>
 * The four lists say what a path is, not only where it is:
 * <ul>
 * <li>{@link #paths()} follows the console: open while the console is open, behind its
 * login once the console authenticates. Dashboards, contracts, figures.</li>
 * <li>{@link #openPaths()} stays reachable without a principal whatever the console does,
 * because something has to be: the assets the login page itself paints with, an endpoint
 * polled by sibling instances that carry no credentials.</li>
 * <li>{@link #writePaths()} requires a principal whatever the console does, because
 * reaching it changes the gateway. A console published without a login is a decision; an
 * API that reconfigures the routing table without one is an accident.</li>
 * <li>{@link #csrfExemptPaths()} is left out of the CSRF protection, for the JSON APIs a
 * client calls with a token rather than with a session opened in a browser.</li>
 * </ul>
 */
public interface SecuredPathsContribution {

	/**
	 * The paths governed by the console: permitted while it is open, authenticated once
	 * it is not.
	 * @return the paths following the console
	 */
	List<String> paths();

	/**
	 * The paths reachable without a principal, whatever the console does.
	 * @return the paths left open, empty by default
	 */
	default List<String> openPaths() {
		return List.of();
	}

	/**
	 * The paths requiring a principal, whatever the console does.
	 * @return the paths always authenticated, empty by default
	 */
	default List<String> writePaths() {
		return List.of();
	}

	/**
	 * The paths left out of the CSRF protection.
	 * @return the paths exempt from CSRF, empty by default
	 */
	default List<String> csrfExemptPaths() {
		return List.of();
	}

}
