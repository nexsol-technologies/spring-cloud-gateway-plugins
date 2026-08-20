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

package ch.nexsol.gateway.ui.security;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;

/**
 * Serves the two pages of the console that sit outside the shell: the login page, and the
 * one telling a signed-in visitor that the console is not theirs to reach. Neither draws
 * a side menu &mdash; the first has no principal to draw it for, the second nothing in it
 * the visitor may open.
 * <p>
 * The login page shows the credentials form, and a button per identity provider the
 * application registered. Both can be offered at once, which is the point: an operator
 * signs in through the provider, and the local user stays as the way in when the provider
 * is unreachable.
 * <p>
 * Only the auto-configuration puts this controller in a context, and only under the mode
 * that needs it: a console left open serves no login page.
 */
@Controller
@RequestMapping("/ui")
public class LoginController {

	private final Map<String, String> providers;

	private final boolean credentialsForm;

	/**
	 * Creates the controller over the identity providers to offer.
	 * @param providers the display name of each provider keyed by registration id, empty
	 * when the application registered none
	 * @param credentialsForm whether there is anything for the credentials form to
	 * authenticate against
	 */
	public LoginController(Map<String, String> providers, boolean credentialsForm) {
		this.providers = providers;
		this.credentialsForm = credentialsForm;
	}

	/**
	 * Renders the login page, saying why it is being shown when something sent the
	 * visitor here.
	 * <p>
	 * The markers are read off the query string rather than through
	 * {@code @RequestParam}, and their presence is what counts, not their value. Every
	 * one of them arrives as a bare flag &mdash; {@code ?error}, {@code ?logout} &mdash;
	 * and WebFlux binds a query parameter carrying no {@code =} to {@code null}, so a
	 * {@code String} parameter is indistinguishable from an absent one and none of these
	 * messages would ever be shown.
	 * @param exchange the exchange carrying the markers
	 * @param model the view model
	 * @return the login page view name
	 */
	@GetMapping("/login")
	public String login(ServerWebExchange exchange, Model model) {
		Set<String> markers = exchange.getRequest().getQueryParams().keySet();
		model.addAttribute("oauth2Providers", this.providers);
		model.addAttribute("credentialsForm", this.credentialsForm);
		model.addAttribute("loginError", markers.contains("error"));
		model.addAttribute("oauth2Error", markers.contains("error_oauth2"));
		model.addAttribute("loggedOut", markers.contains("logout"));
		model.addAttribute("unauthorized", markers.contains(UiAuthenticationEntryPoint.UNAUTHORIZED_PARAMETER));
		return "dashboard/login";
	}

	/**
	 * Renders the page shown to a signed-in visitor the console turned away. It carries
	 * the way out: signing in again would hand back the same roles, so what is left to do
	 * is sign out and come back as somebody else.
	 * @return the view name of the page
	 */
	@GetMapping("/forbidden")
	public String forbidden() {
		return "dashboard/forbidden";
	}

}
