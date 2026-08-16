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
 * The declaration a plugin publishes as a bean, one list per kind of path.
 * <p>
 * The factory methods are the readable way in: a contribution says what its paths are,
 * and the console decides what that means.
 *
 * @param paths the paths following the console
 * @param openPaths the paths reachable without a principal
 * @param writePaths the paths always requiring a principal
 * @param csrfExemptPaths the paths left out of the CSRF protection
 */
public record SecuredPaths(List<String> paths, List<String> openPaths, List<String> writePaths,
		List<String> csrfExemptPaths) implements SecuredPathsContribution {

	/**
	 * Creates a contribution, copying the lists so a caller cannot change what it
	 * declared.
	 * @param paths the paths following the console
	 * @param openPaths the paths reachable without a principal
	 * @param writePaths the paths always requiring a principal
	 * @param csrfExemptPaths the paths left out of the CSRF protection
	 */
	public SecuredPaths {
		paths = List.copyOf(paths);
		openPaths = List.copyOf(openPaths);
		writePaths = List.copyOf(writePaths);
		csrfExemptPaths = List.copyOf(csrfExemptPaths);
	}

	/**
	 * Declares paths that follow the console: open while it is open, behind its login
	 * once it authenticates.
	 * @param paths the exact paths
	 * @return the contribution
	 */
	public static SecuredPaths governed(String... paths) {
		return new SecuredPaths(List.of(paths), List.of(), List.of(), List.of());
	}

	/**
	 * Declares paths that stay reachable without a principal, whatever the console does.
	 * @param paths the exact paths
	 * @return the contribution
	 */
	public static SecuredPaths open(String... paths) {
		return new SecuredPaths(List.of(), List.of(paths), List.of(), List.of());
	}

	/**
	 * Declares paths that always require a principal because reaching them changes the
	 * gateway, and that a browser reaches through a form or an HTMX fragment: they keep
	 * the CSRF protection of the console.
	 * @param paths the exact paths
	 * @return the contribution
	 */
	public static SecuredPaths write(String... paths) {
		return new SecuredPaths(List.of(), List.of(), List.of(paths), List.of());
	}

	/**
	 * Declares paths that always require a principal and that a client calls with a token
	 * rather than with a session: they are left out of the CSRF protection, which a
	 * client holding no session cannot satisfy and does not need.
	 * @param paths the exact paths
	 * @return the contribution
	 */
	public static SecuredPaths api(String... paths) {
		return new SecuredPaths(List.of(), List.of(), List.of(paths), List.of(paths));
	}

}
