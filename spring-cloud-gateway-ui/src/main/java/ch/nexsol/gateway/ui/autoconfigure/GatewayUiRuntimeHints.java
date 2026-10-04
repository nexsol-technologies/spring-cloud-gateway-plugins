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

package ch.nexsol.gateway.ui.autoconfigure;

import ch.nexsol.gateway.ui.audit.AuditEventView;
import ch.nexsol.gateway.ui.branding.GatewayUiBrandingProperties;
import ch.nexsol.gateway.ui.insights.ActuatorInstances;
import ch.nexsol.gateway.ui.insights.InsightsController;
import ch.nexsol.gateway.ui.metrics.InstanceMetricsView;
import ch.nexsol.gateway.ui.nav.NavItem;
import ch.nexsol.gateway.ui.nav.NavSection;
import ch.nexsol.gateway.ui.overview.OverviewStat;
import ch.nexsol.gateway.ui.passivescan.FindingView;
import ch.nexsol.gateway.ui.routes.DatabaseRoutesController;
import ch.nexsol.gateway.ui.routes.PredicateOutcome;
import ch.nexsol.gateway.ui.routes.RouteMatch;
import ch.nexsol.gateway.ui.routes.RouteTestReport;
import ch.nexsol.gateway.ui.routes.RouteView;
import ch.nexsol.gateway.ui.view.KeyValue;
import org.thymeleaf.expression.Lists;
import org.thymeleaf.expression.Strings;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers, for a native image, the reflection the console reads its view models
 * through.
 * <p>
 * A Thymeleaf template resolves {@code ${item.label()}} through SpEL, which looks the
 * accessor up on the model object with {@code Class.getMethods()}: a view model carrying
 * no hint has no member for SpEL to find, and the expression fails rather than rendering
 * empty. Three of them are answered as JSON as well, and Jackson 3 looks a record's
 * canonical constructor up even to write it, so the constructors are opened too; the
 * fields are what Jackson falls back on, SpEL reading public members only. Nothing else
 * registers them &mdash; the signature of a controller method is not walked, and
 * {@code spring-boot-thymeleaf} contributes no hints of its own.
 * <p>
 * The member categories are registered rather than the members themselves, for two
 * reasons. A template calls the accessors a record derives as well as its components, and
 * only the category covers those: {@code NavSection.id()} is read by the shared layout of
 * every page, and {@code GatewayUiBrandingProperties.darkLogo()} by the home and login
 * pages &mdash; Spring Boot registers that class for binding, which covers
 * {@code getLogo} and {@code getName} but not a method the binder never calls. And naming
 * the members means walking them: {@code BindingReflectionHintsRegistrar} reads a type
 * with {@code Class.getMethods()}, which resolves the parameter types of every method, so
 * {@code AuditEventView.of(AuditEvent)} would load the audit module &mdash; an optional
 * dependency here &mdash; and fail the build of a console carrying no audit.
 * <p>
 * The expression utilities the templates call &mdash; {@code #lists}, {@code #strings}
 * &mdash; are Thymeleaf classes reached through SpEL the same way, and registered here as
 * well. The GraalVM metadata repository registers them too, but conditioned on the OGNL
 * evaluator, which a Spring application never reaches since it evaluates through SpEL,
 * and leaves {@code Lists} out altogether: {@code #lists.contains} fails on the first
 * page.
 * <p>
 * The three records the console never renders are left out on purpose:
 * {@code UiSecuredPaths} and {@code UiLoginRegistrations} are beans the security
 * auto-configuration resolves, and {@code UiLoginProviders} is unwrapped into the map the
 * login page iterates.
 */
class GatewayUiRuntimeHints implements RuntimeHintsRegistrar {

	/**
	 * Every view model the console renders, the nested ones named alongside the records
	 * holding them: nothing here is reached by walking a type, so a view model left out
	 * of this list carries no hint at all.
	 */
	/**
	 * The Thymeleaf expression utilities the templates call; {@code #lists} is
	 * {@link Lists}, and so on.
	 */
	private static final Class<?>[] EXPRESSION_UTILITIES = { Lists.class, Strings.class };

	private static final Class<?>[] VIEW_MODELS = { AuditEventView.class, ActuatorInstances.Instance.class,
			InsightsController.View.class, InstanceMetricsView.class, NavItem.class, NavSection.class,
			OverviewStat.class, FindingView.class, DatabaseRoutesController.ElementRowView.class,
			PredicateOutcome.class, RouteMatch.class, RouteTestReport.class, RouteView.class, KeyValue.class };

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		for (Class<?> viewModel : VIEW_MODELS) {
			hints.reflection()
				.registerType(viewModel, MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.ACCESS_DECLARED_FIELDS,
						MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
		}
		hints.reflection().registerType(GatewayUiBrandingProperties.class, MemberCategory.INVOKE_DECLARED_METHODS);
		for (Class<?> expressionUtility : EXPRESSION_UTILITIES) {
			hints.reflection().registerType(expressionUtility, MemberCategory.INVOKE_PUBLIC_METHODS);
		}
	}

}
