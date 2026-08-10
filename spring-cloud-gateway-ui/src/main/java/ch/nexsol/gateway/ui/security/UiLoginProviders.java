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

/**
 * The identity providers the login page offers a button for, keyed by registration id and
 * reading under the client name of the registration.
 * <p>
 * Resolved once at start-up from the client registrations of the application, so the
 * login page needs nothing of the OAuth2 API to render: without the client module on the
 * classpath there is no such bean, and the page shows the form alone.
 *
 * @param providers the display name of each provider, keyed by registration id
 */
public record UiLoginProviders(Map<String, String> providers) {
}
