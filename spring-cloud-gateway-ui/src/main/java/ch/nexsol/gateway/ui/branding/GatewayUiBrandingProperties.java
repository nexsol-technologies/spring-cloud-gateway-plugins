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

package ch.nexsol.gateway.ui.branding;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Configuration properties for the logo of the login page and of the home page.
 * <p>
 * Unset, both pages carry the lockup of the plugins. Set, they carry the logo of the
 * organisation running the console instead, and the plugins sign the page in a corner.
 * The icon of the side menu is not affected.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.ui.branding")
public class GatewayUiBrandingProperties {

	/**
	 * The logo replacing the lockup on the login page and on the home page: a path the
	 * application serves, starting with {@code /}, or an absolute URL. Both pages scale
	 * it to fit 280 by 80 CSS pixels, so a landscape drawing reads best.
	 */
	private String logo;

	/**
	 * The logo drawn when the dark theme is active. Unset, the light one is drawn on both
	 * themes.
	 */
	private String logoDark;

	/**
	 * The name of the organisation the logo stands for, read by screen readers in place
	 * of the image.
	 */
	private String name;

	/**
	 * Returns the logo replacing the lockup, or {@code null} to keep the lockup.
	 * @return the logo path or URL, or {@code null}
	 */
	public String getLogo() {
		return this.logo;
	}

	/**
	 * Sets the logo replacing the lockup. A blank value keeps the lockup, as an unset one
	 * does. A path is checked here, so a wrong one fails the binding of the property
	 * rather than the building of the security chain.
	 * @param logo the logo path or URL
	 */
	public void setLogo(String logo) {
		this.logo = checked(logo);
	}

	/**
	 * Returns the logo drawn on the dark theme, or {@code null} when none was set.
	 * @return the dark logo path or URL, or {@code null}
	 */
	public String getLogoDark() {
		return this.logoDark;
	}

	/**
	 * Sets the logo drawn on the dark theme, checked as {@link #setLogo(String)} checks
	 * the light one.
	 * @param logoDark the dark logo path or URL
	 */
	public void setLogoDark(String logoDark) {
		this.logoDark = checked(logoDark);
	}

	/**
	 * Returns the name of the organisation the logo stands for.
	 * @return the name, or {@code null}
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * Sets the name of the organisation the logo stands for.
	 * @param name the name
	 */
	public void setName(String name) {
		this.name = StringUtils.hasText(name) ? name : null;
	}

	/**
	 * Returns the logo the dark theme draws: the dark one when set, the light one
	 * otherwise, and nothing without a light one, since the pages then draw the lockup.
	 * @return the logo path or URL for the dark theme, or {@code null} when no logo is
	 * set
	 */
	public String darkLogo() {
		if (this.logo == null) {
			return null;
		}
		return (this.logoDark != null) ? this.logoDark : this.logo;
	}

	/**
	 * Returns the paths of the logos the application serves itself, the ones the security
	 * chain of the console has to leave open for the login page to paint with. A logo
	 * served elsewhere is left out, and so is a dark logo with no light one, since the
	 * pages then draw the lockup.
	 * <p>
	 * A path is returned without its query string: the chain matches what the browser
	 * asks for, and a {@code ?v=2} left in would read as a pattern character.
	 * @return the relative logo paths, empty when nothing is to be opened
	 */
	public List<String> servedPaths() {
		List<String> paths = new ArrayList<>();
		if (this.logo != null) {
			for (String value : new String[] { this.logo, this.logoDark }) {
				String path = servedPath(value);
				if (path != null && !paths.contains(path)) {
					paths.add(path);
				}
			}
		}
		return paths;
	}

	/**
	 * Returns the value to keep for a logo: {@code null} for a blank one, the value
	 * itself for an absolute URL, and for a path the value once it is known to start with
	 * {@code /} and to carry no pattern character. The chain would read {@code /img/*} as
	 * a wildcard and {@code /**} as the whole console.
	 * @param value the configured value
	 * @return the value to keep, or {@code null}
	 */
	private static String checked(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String path = servedPath(value);
		if (path != null) {
			if (!path.startsWith("/")) {
				throw new IllegalArgumentException(
						"A logo path must start with '/', as '/img/logo.png' does: " + value);
			}
			if (path.contains("*") || path.contains("{") || path.contains("}")) {
				throw new IllegalArgumentException(
						"A logo path names one file, and cannot carry '*', '{' or '}': " + value);
			}
		}
		return value;
	}

	/**
	 * Returns the path a configured value asks the application to serve, or {@code null}
	 * when the value is served by somebody else: an absolute URL, or one starting with
	 * {@code //} that the browser completes with the scheme of the page.
	 * @param value the configured value
	 * @return the raw path without its query string, or {@code null}
	 */
	private static String servedPath(String value) {
		if (value == null) {
			return null;
		}
		URI uri;
		try {
			uri = URI.create(value);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Not a path or a URL: " + value, ex);
		}
		if (uri.isAbsolute() || uri.getRawAuthority() != null) {
			return null;
		}
		return (uri.getRawPath() != null) ? uri.getRawPath() : "";
	}

}
