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

package ch.nexsol.gateway.ui.nav;

/**
 * A single entry in the gateway UI side menu.
 * <p>
 * Plugins activate their own menu entry, Spring Boot Admin style, simply by declaring a
 * {@code NavItem} bean (optionally guarded by {@code @ConditionalOnClass} or
 * {@code @ConditionalOnBean}): the {@link GatewayUiMenu} collects every such bean present
 * in the context and the shell renders them in the sidebar.
 *
 * @param id the stable identifier used to flag the active entry, e.g. {@code home}
 * @param label the text shown next to the icon when the menu is expanded
 * @param icon the id of the SVG symbol (defined in the shell sprite) rendered as the icon
 * @param href the target URL the entry links to
 * @param order the sort weight; lower values appear first
 * @param group the heading this entry folds under, {@code null} for an entry sitting on
 * its own at the top level. Entries carrying the same group are drawn together under it,
 * wherever their order puts the group as a whole.
 */
public record NavItem(String id, String label, String icon, String href, int order, String group) {

	/**
	 * Creates an entry sitting at the top level of the menu, under no heading.
	 * <p>
	 * Kept so a plugin written against the console before menu groups existed still
	 * compiles and still links against this class unchanged.
	 * @param id the stable identifier used to flag the active entry
	 * @param label the text shown next to the icon when the menu is expanded
	 * @param icon the id of the SVG symbol rendered as the icon
	 * @param href the target URL the entry links to
	 * @param order the sort weight; lower values appear first
	 */
	public NavItem(String id, String label, String icon, String href, int order) {
		this(id, label, icon, href, order, null);
	}

}
