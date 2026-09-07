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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Registry of the {@link NavItem} entries contributed to the gateway UI side menu.
 * <p>
 * Every {@link NavItem} bean declared in the application context is gathered here and
 * exposed to the shell template already sorted by {@link NavItem#order()} then
 * {@link NavItem#label()}. A plugin therefore lights up its own menu entry just by adding
 * a conditionally-declared {@code NavItem} bean, without this module knowing about it.
 */
public class GatewayUiMenu {

	private final ObjectProvider<NavItem> items;

	/**
	 * Creates the menu backed by the lazily-resolved collection of contributed entries.
	 * @param items the provider over every {@link NavItem} bean in the context
	 */
	public GatewayUiMenu(ObjectProvider<NavItem> items) {
		this.items = items;
	}

	/**
	 * Returns the contributed menu entries sorted by order then label.
	 * @return the immutable, ordered list of menu entries
	 */
	public List<NavItem> items() {
		return this.items.orderedStream()
			.sorted(Comparator.comparingInt(NavItem::order).thenComparing(NavItem::label))
			.toList();
	}

	/**
	 * Returns the same entries laid out as the shell draws them: a section per group, and
	 * a section of its own for every entry carrying none.
	 * <p>
	 * A group sits where its first entry would have sat, so a plugin joins a group by
	 * naming it and still decides where the group as a whole lands through its order. The
	 * entries stay in the order {@link #items()} put them in.
	 * @return the immutable, ordered list of sections
	 */
	public List<NavSection> sections() {
		Map<String, List<NavItem>> groups = new LinkedHashMap<>();
		List<NavSection> sections = new ArrayList<>();
		for (NavItem item : items()) {
			if (item.group() == null) {
				sections.add(new NavSection(null, List.of(item)));
				continue;
			}
			List<NavItem> group = groups.get(item.group());
			if (group == null) {
				group = new ArrayList<>();
				groups.put(item.group(), group);
				// A placeholder holding the place of the group, filled in below: the list
				// it carries is the one still being appended to.
				sections.add(new NavSection(item.group(), List.of(item)));
			}
			group.add(item);
		}
		return sections.stream()
			.map((section) -> (section.group() == null) ? section
					: new NavSection(section.group(), groups.get(section.group())))
			.toList();
	}

}
