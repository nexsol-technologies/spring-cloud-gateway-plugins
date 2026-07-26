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

package ch.nexsol.gateway.ui.overview;

/**
 * A single figure shown as a tile on the home page.
 *
 * @param label what the figure is, e.g. {@code Routes}
 * @param value the figure itself, already formatted for display
 * @param detail a one-line breakdown shown under the value, or {@code null} for none
 * @param order the sort weight; lower values appear first
 */
public record OverviewStat(String label, String value, String detail, int order) {
}
