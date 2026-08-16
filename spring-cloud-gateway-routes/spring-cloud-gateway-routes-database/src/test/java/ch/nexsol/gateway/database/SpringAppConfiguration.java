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

package ch.nexsol.gateway.database;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Application backing the tests of this module.
 * <p>
 * The auto-configuration package is left out of the scan. An {@code @AutoConfiguration}
 * class is a {@code @Configuration} class, so scanning would register it as an ordinary
 * one &mdash; skipping the ordering its conditions are written against, and giving these
 * tests a wiring no application ever gets. What it declares reaches this context the way
 * it reaches a gateway: through {@code @EnableAutoConfiguration}.
 */
@SpringBootConfiguration
@ComponentScan(excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
		pattern = "ch\\.nexsol\\.gateway\\.database\\.autoconfigure\\..*"))
@EnableAutoConfiguration
public class SpringAppConfiguration {

	public static void main(String[] args) {
		SpringApplication.run(SpringAppConfiguration.class, args);
	}

}
