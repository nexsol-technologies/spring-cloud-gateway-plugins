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

package ch.nexsol.gateway.database.autoconfigure;

import ch.nexsol.gateway.database.controller.FilterController;
import ch.nexsol.gateway.database.controller.PredicateController;
import ch.nexsol.gateway.database.controller.RouteController;
import ch.nexsol.gateway.database.controller.error.ControllerExceptionHandler;
import ch.nexsol.gateway.database.locator.DatabaseRouteDefinitionLocator;
import ch.nexsol.gateway.database.service.ApiService;
import ch.nexsol.gateway.database.service.ArgumentService;
import ch.nexsol.gateway.database.service.FilterService;
import ch.nexsol.gateway.database.service.GatewayConfigService;
import ch.nexsol.gateway.database.service.PredicateService;
import ch.nexsol.gateway.database.service.RouteService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@AutoConfiguration
@Import({ DatabaseRouteDefinitionLocator.class, ControllerExceptionHandler.class, RouteController.class,
		FilterController.class, PredicateController.class, GatewayConfigService.class, ApiService.class,
		RouteService.class, PredicateService.class, FilterService.class, ArgumentService.class })
@EnableR2dbcRepositories(basePackages = "ch.nexsol.gateway.database.repository")
@EntityScan(basePackages = "ch.nexsol.gateway.database.entity")
public class GatewayDatabaseAutoConfiguration {

}
