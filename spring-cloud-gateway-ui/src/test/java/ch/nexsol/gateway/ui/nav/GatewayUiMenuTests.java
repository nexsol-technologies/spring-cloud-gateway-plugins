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

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GatewayUiMenu}.
 */
class GatewayUiMenuTests {

	@Test
	void ordersTheEntriesByOrderThenLabel() {
		GatewayUiMenu menu = menuOf(item("b", 10, null), item("a", 10, null), item("home", 0, null));

		assertThat(menu.items()).extracting(NavItem::id).containsExactly("home", "a", "b");
	}

	@Test
	void givesAnUngroupedEntryASectionOfItsOwn() {
		GatewayUiMenu menu = menuOf(item("home", 0, null), item("audit", 30, null));

		assertThat(menu.sections()).extracting(NavSection::group).containsExactly(null, null);
		assertThat(menu.sections().get(0).items()).extracting(NavItem::id).containsExactly("home");
	}

	@Test
	void gathersTheEntriesOfOneGroupUnderASingleSection() {
		GatewayUiMenu menu = menuOf(item("home", 0, null), item("flow", 19, "Activity"),
				item("traffic", 20, "Activity"), item("graph", 22, "Activity"), item("audit", 30, null));

		assertThat(menu.sections()).extracting(NavSection::group).containsExactly(null, "Activity", null);
		assertThat(menu.sections().get(1).items()).extracting(NavItem::id).containsExactly("flow", "traffic", "graph");
	}

	/**
	 * A group lands where its first entry would have landed, so an ungrouped entry whose
	 * order falls in the middle of a group is pushed past it rather than splitting it in
	 * two.
	 */
	@Test
	void putsTheGroupWhereItsFirstEntryWouldHaveGone() {
		GatewayUiMenu menu = menuOf(item("flow", 19, "Activity"), item("runtime", 21, null),
				item("graph", 22, "Activity"));

		assertThat(menu.sections()).extracting(NavSection::group).containsExactly("Activity", null);
		assertThat(menu.sections().get(0).items()).extracting(NavItem::id).containsExactly("flow", "graph");
	}

	@Test
	void namesASectionAfterTheHeadingItFoldsUnder() {
		assertThat(new NavSection("Activity", List.of(item("flow", 19, "Activity"))).id()).isEqualTo("activity");
		assertThat(new NavSection(null, List.of(item("home", 0, null))).id()).isNull();
	}

	/**
	 * The console had no menu groups before, and a plugin written against that console
	 * still declares its entry with five arguments.
	 */
	@Test
	void leavesAnEntryDeclaredWithoutAGroupAtTheTopLevel() {
		assertThat(new NavItem("home", "Home", "icon-home", "/ui", 0).group()).isNull();
	}

	private static NavItem item(String id, int order, String group) {
		return new NavItem(id, id, "icon-plugin", "/ui/" + id, order, group);
	}

	private static GatewayUiMenu menuOf(NavItem... items) {
		return new GatewayUiMenu(new ObjectProvider<>() {
			@Override
			public Stream<NavItem> orderedStream() {
				return Stream.of(items);
			}

			@Override
			public NavItem getObject() {
				throw new UnsupportedOperationException();
			}

			@Override
			public NavItem getObject(Object... args) {
				throw new UnsupportedOperationException();
			}

			@Override
			public NavItem getIfAvailable() {
				return null;
			}

			@Override
			public NavItem getIfUnique() {
				return null;
			}
		});
	}

}
