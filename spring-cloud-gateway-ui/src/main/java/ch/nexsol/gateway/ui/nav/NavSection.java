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

import java.util.Collections;
import java.util.List;

/**
 * One run of the side menu, as the shell renders it: either a single entry standing on
 * its own, or a heading with the entries that fold under it.
 * <p>
 * The shell iterates over sections rather than over entries so a group and a lone entry
 * are the same thing to the template. A section is never both: {@link #group()} is
 * {@code null} for the first kind and set for the second.
 *
 * @param group the heading the entries fold under, {@code null} for a lone entry
 * @param items the entries of the section, already ordered; exactly one when the section
 * carries no heading
 */
public record NavSection(String group, List<NavItem> items) {

	public NavSection {
		items = Collections.unmodifiableList(List.copyOf(items));
	}

	/**
	 * @return the identifier the shell remembers the folded state of this section under,
	 * derived from the heading; {@code null} for a lone entry, which never folds
	 */
	public String id() {
		return (this.group != null) ? this.group.toLowerCase().replace(' ', '-') : null;
	}

}
