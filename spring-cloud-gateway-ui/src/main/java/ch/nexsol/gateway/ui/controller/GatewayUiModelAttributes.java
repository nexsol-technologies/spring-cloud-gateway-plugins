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

package ch.nexsol.gateway.ui.controller;

import java.util.List;

import ch.nexsol.gateway.ui.nav.GatewayUiMenu;
import ch.nexsol.gateway.ui.nav.NavItem;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the side-menu entries to every server-rendered view, so any page hosted in the
 * shell &mdash; including those contributed by other gateway plugins &mdash; gets the
 * sidebar populated without depending on this module.
 */
@ControllerAdvice
public class GatewayUiModelAttributes {

	private final GatewayUiMenu menu;

	/**
	 * Creates the advice backed by the menu registry.
	 * @param menu the registry of contributed side-menu entries
	 */
	public GatewayUiModelAttributes(GatewayUiMenu menu) {
		this.menu = menu;
	}

	/**
	 * Adds the ordered side-menu entries to the model of every view.
	 * @return the ordered menu entries
	 */
	@ModelAttribute("navItems")
	public List<NavItem> navItems() {
		return this.menu.items();
	}

}
