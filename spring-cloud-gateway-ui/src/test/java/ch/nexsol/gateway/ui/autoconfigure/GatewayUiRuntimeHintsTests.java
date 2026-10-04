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

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.nexsol.gateway.ui.branding.GatewayUiBrandingProperties;
import ch.nexsol.gateway.ui.security.UiLoginProviders;
import ch.nexsol.gateway.ui.security.UiLoginRegistrations;
import ch.nexsol.gateway.ui.security.UiSecuredPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.aot.AotServices;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GatewayUiRuntimeHints}.
 * <p>
 * The coverage test scans the module rather than listing the view models: one added later
 * is picked up here and fails the build until it is registered, which is the only warning
 * there is &mdash; a missing hint costs nothing on the JVM and only surfaces as a
 * template that fails to render in a native image.
 */
class GatewayUiRuntimeHintsTests {

	private static final CodeSource MODULE_CLASSES = GatewayUiRuntimeHints.class.getProtectionDomain().getCodeSource();

	/**
	 * The records the console never renders: two are beans the security
	 * auto-configuration resolves, and the third is unwrapped into the map the login page
	 * iterates. A record added here takes a line of reasoning with it, not just a line of
	 * code.
	 */
	private static final Set<Class<?>> NEVER_RENDERED = Set.of(UiSecuredPaths.class, UiLoginRegistrations.class,
			UiLoginProviders.class);

	private final RuntimeHints hints = new RuntimeHints();

	@BeforeEach
	void registerHints() {
		new GatewayUiRuntimeHints().registerHints(this.hints, getClass().getClassLoader());
	}

	@Test
	void opensTheMemberCategoriesOnEveryViewModel() {
		// SpEL resolves ${item.label()} through Class.getMethods(), and a template calls
		// the accessors a record derives as well as its components: NavSection.id() is
		// read by the shared layout of every page and is not a component. Only the method
		// category covers it. The constructors are what Jackson 3 looks up to write the
		// records the console answers as JSON, and the fields what it falls back on.
		List<Class<?>> records = recordsOf("ch.nexsol.gateway.ui");
		assertThat(records).isNotEmpty();
		for (Class<?> viewModel : records) {
			assertThat(RuntimeHintsPredicates.reflection()
				.onType(viewModel)
				.withMemberCategories(MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.ACCESS_DECLARED_FIELDS,
						MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
				.as("%s does not carry the three categories; a template or a JSON feed could not read it",
						viewModel.getName())
				.accepts(this.hints);
		}
	}

	@Test
	void registersTheBrandingMethodTheBinderNeverCalls() {
		// Spring Boot registers the class for binding, which covers getLogo and getName;
		// darkLogo() is read by the home and login pages and is not one of them.
		assertThat(RuntimeHintsPredicates.reflection()
			.onType(GatewayUiBrandingProperties.class)
			.withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS)).accepts(this.hints);
	}

	@Test
	void namesNoMemberSoNoOptionalDependencyIsLoaded() {
		// A hint naming a member has to be obtained by walking the type, and
		// BindingReflectionHintsRegistrar walks it with Class.getMethods(), which
		// resolves the parameter types of every method. AuditEventView.of(AuditEvent)
		// would load the audit module, which is optional here, and process-aot would
		// fail for a console carrying no audit. Categories name no member.
		this.hints.reflection().typeHints().forEach((hint) -> {
			assertThat(hint.methods()).as("%s names a member, so something walked it", hint.getType()).isEmpty();
			assertThat(hint.fields()).as("%s names a member, so something walked it", hint.getType()).isEmpty();
		});
	}

	@Test
	void registersEveryExpressionUtilityTheTemplatesCall() throws IOException {
		// #lists.contains(...) resolves on org.thymeleaf.expression.Lists through SpEL, a
		// class the templates reach by name: a utility used in a template and not
		// registered fails the page in a native image, and nothing but this test says so.
		Set<String> utilities = new TreeSet<>();
		Pattern call = Pattern.compile("#([a-zA-Z]+)\\.");
		for (Resource template : new PathMatchingResourcePatternResolver()
			.getResources("classpath*:templates/**/*.html")) {
			Matcher matcher = call.matcher(template.getContentAsString(StandardCharsets.UTF_8));
			while (matcher.find()) {
				utilities.add(matcher.group(1));
			}
		}
		assertThat(utilities).isNotEmpty();
		for (String utility : utilities) {
			String simpleName = utility.equals("execInfo") ? "ExecutionInfo"
					: Character.toUpperCase(utility.charAt(0)) + utility.substring(1);
			assertThat(RuntimeHintsPredicates.reflection()
				.onType(TypeReference.of("org.thymeleaf.expression." + simpleName))
				.withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
				.as("#%s is called by a template and org.thymeleaf.expression.%s carries no hint", utility, simpleName)
				.accepts(this.hints);
		}
	}

	@Test
	void isContributedThroughAotFactories() {
		// The loader the build itself uses, so the resource location cannot drift.
		List<RuntimeHintsRegistrar> registrars = AotServices.factories(getClass().getClassLoader())
			.load(RuntimeHintsRegistrar.class)
			.asList();
		assertThat(registrars).hasAtLeastOneElementOfType(GatewayUiRuntimeHints.class);
	}

	private static List<Class<?>> recordsOf(String basePackage) {
		// The nested records are wanted too, so the candidate check that would keep only
		// top level components is lifted and the record test is applied instead.
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				return true;
			}
		};
		scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
		List<Class<?>> records = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
			Class<?> candidate = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
			if (candidate.isRecord() && Modifier.isPublic(candidate.getModifiers())
					&& !NEVER_RENDERED.contains(candidate) && isShippedByThisModule(candidate)) {
				records.add(candidate);
			}
		}
		return records;
	}

	private static boolean isShippedByThisModule(Class<?> candidate) {
		// The scan sees the test classpath as well; only what the module ships needs a
		// hint, and a fixture declared in a test would otherwise fail the build. The
		// registrar is the reference: whatever it was loaded from is this module.
		CodeSource codeSource = candidate.getProtectionDomain().getCodeSource();
		return codeSource != null && MODULE_CLASSES != null
				&& codeSource.getLocation().equals(MODULE_CLASSES.getLocation());
	}

}
