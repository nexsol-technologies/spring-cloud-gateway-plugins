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

package ch.nexsol.gateway.dbwiring;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * An application wired the way a gateway is: everything comes from the
 * auto-configuration, nothing from a component scan.
 * <p>
 * It lives outside {@code ch.nexsol.gateway.database} on purpose. The application backing
 * the other tests of this module scans that package, so its context holds the controllers
 * whether or not the plugin decided to publish them &mdash; which is precisely what the
 * tests using this one are about.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class AutoConfiguredApplication {

}
