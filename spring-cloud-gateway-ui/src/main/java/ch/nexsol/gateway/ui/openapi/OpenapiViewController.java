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

package ch.nexsol.gateway.ui.openapi;

import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the OpenAPI view: the contracts aggregated by the OpenAPI hub, rendered inside
 * the shell.
 * <p>
 * The page carries the two SpringDoc URLs it reads at runtime, so a custom
 * {@code springdoc.api-docs.path} is honoured without this module depending on SpringDoc.
 * The configured vendor extensions travel to the page the same way, so declaring one is a
 * matter of configuration rather than of rebuilding the page script.
 */
@Controller
@RequestMapping("/ui/openapi")
public class OpenapiViewController {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String documentUrl;

	private final String configUrl;

	private final String extensionLabels;

	/**
	 * Creates the controller from the configured SpringDoc documentation path.
	 * <p>
	 * The properties are resolved through a provider: an application component-scanning
	 * this package picks the controller up outside the auto-configuration that binds
	 * them, and a view with no declared extension is preferable to a context that fails.
	 * @param apiDocsPath the SpringDoc documentation path
	 * @param properties the provider over the OpenAPI view properties
	 */
	public OpenapiViewController(@Value("${springdoc.api-docs.path:/v3/api-docs}") String apiDocsPath,
			ObjectProvider<OpenapiViewProperties> properties) {
		this.documentUrl = apiDocsPath;
		this.configUrl = apiDocsPath + "/swagger-config";
		this.extensionLabels = MAPPER
			.writeValueAsString(properties.getIfAvailable(OpenapiViewProperties::new).getExtensions());
	}

	/**
	 * Renders the OpenAPI page inside the shell.
	 * @param model the view model
	 * @return the page view name
	 */
	@GetMapping
	public String page(Model model) {
		model.addAttribute("activeNav", "openapi");
		model.addAttribute("openapiDocumentUrl", this.documentUrl);
		model.addAttribute("openapiConfigUrl", this.configUrl);
		model.addAttribute("openapiExtensionLabels", this.extensionLabels);
		return "dashboard/openapi";
	}

}
