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

import reactor.core.publisher.Flux;

/**
 * Source of figures for the home page overview.
 * <p>
 * Each view contributes the numbers it owns through its own implementation, declared next
 * to the view and guarded by the same condition. That keeps the home page free of any
 * reference to the optional types those views are built on &mdash; the gateway route
 * table, the meter registry or the audit plugin &mdash; so it still renders when they are
 * absent.
 */
@FunctionalInterface
public interface OverviewContribution {

	/**
	 * Reads the current figures. Called on every home page render, so implementations
	 * must not block the event loop.
	 * @return the contributed figures
	 */
	Flux<OverviewStat> stats();

}
