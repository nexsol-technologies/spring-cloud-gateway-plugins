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

import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;

/**
 * The client registrations the console signs in through, resolved once at start-up and
 * held apart from the ones the application registered.
 * <p>
 * On a gateway the two are rarely the same thing. What sits under
 * {@code spring.security.oauth2.client} is the technical plumbing the routes relay tokens
 * with, and a login page offering a button per one of those shows an operator a list of
 * internal clients. Under
 * {@code spring.cloud.gateway.server.webflux.ui.security.spring.security.oauth2.client},
 * {@code use} names the ones the console keeps out of them, and {@code registration} with
 * {@code provider} declare registrations of its own instead. A registration that is not
 * an authorization code client is left out either way: no browser could complete the
 * grant its button would start.
 * <p>
 * Wrapping the repository rather than publishing it as a
 * {@link ReactiveClientRegistrationRepository} bean is deliberate: a second bean of that
 * type would make the one the application injects ambiguous, and the gateway would fail
 * to start.
 *
 * @param repository the registrations the console offers on its login page, or
 * {@code null} when none of the ones found can sign a visitor in
 */
public record UiLoginRegistrations(ReactiveClientRegistrationRepository repository) {
}
