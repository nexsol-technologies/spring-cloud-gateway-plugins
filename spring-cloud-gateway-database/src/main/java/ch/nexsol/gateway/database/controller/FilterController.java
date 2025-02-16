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

package ch.nexsol.gateway.database.controller;

import java.util.Map;

import ch.nexsol.gateway.database.service.GatewayConfigService;
import reactor.core.publisher.Flux;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gateway/routes")
public class FilterController {

	private final GatewayConfigService gatewayConfigService;

	public FilterController(GatewayConfigService gatewayConfigService) {
		this.gatewayConfigService = gatewayConfigService;
	}

	@GetMapping(path = "/available-filters")
	public Flux<Map<String, Object>> getAvailableFiltersWithArgs() {
		return this.gatewayConfigService.getAvailableFiltersWithArgs();
	}

	@GetMapping(path = "/available-filters/{filter}/args")
	public Flux<CharSequence> getArgs(@PathVariable String filter) {
		return this.gatewayConfigService.getArgsForFilter(filter);
	}

}
