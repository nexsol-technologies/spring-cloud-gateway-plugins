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

import java.util.Comparator;
import java.util.List;

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

}
