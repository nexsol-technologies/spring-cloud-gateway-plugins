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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the menu is built once. It is put in the model of every request an annotated
 * controller serves &mdash; the JSON a view polls every few seconds included &mdash; and
 * its entries are beans, which do not change once the gateway has started.
 */
class GatewayUiMenuCachingTests {

	private final AtomicInteger resolutions = new AtomicInteger();

	@Test
	void resolvesTheEntriesOnceHoweverOftenTheMenuIsAskedFor() {
		GatewayUiMenu menu = menuOf(new NavItem("b", "b", "icon-plugin", "/ui/b", 20, "Activity"),
				new NavItem("a", "a", "icon-plugin", "/ui/a", 10, null));

		for (int request = 0; request < 5; request++) {
			assertThat(menu.items()).extracting(NavItem::id).containsExactly("a", "b");
			assertThat(menu.sections()).extracting(NavSection::group).containsExactly(null, "Activity");
		}

		assertThat(this.resolutions).hasValue(1);
	}

	@Test
	void givesTheSameMenuToEveryRequest() {
		GatewayUiMenu menu = menuOf(new NavItem("a", "a", "icon-plugin", "/ui/a", 10, null));

		assertThat(menu.items()).isSameAs(menu.items());
		assertThat(menu.sections()).isSameAs(menu.sections());
	}

	private GatewayUiMenu menuOf(NavItem... items) {
		return new GatewayUiMenu(new ObjectProvider<>() {
			@Override
			public Stream<NavItem> orderedStream() {
				GatewayUiMenuCachingTests.this.resolutions.incrementAndGet();
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
