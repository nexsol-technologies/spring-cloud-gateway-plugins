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

package ch.nexsol.gateway.ui.branding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link GatewayUiBrandingProperties}: what the dark theme draws, and which of
 * the configured logos the security chain has to leave open.
 */
class GatewayUiBrandingPropertiesTests {

	@Test
	void darkThemeDrawsTheLightLogoWhenNoDarkOneIsSet() {
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		properties.setLogo("/img/acme.png");
		assertThat(properties.darkLogo()).isEqualTo("/img/acme.png");
	}

	@Test
	void darkThemeDrawsTheDarkLogoWhenSet() {
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		properties.setLogo("/img/acme.png");
		properties.setLogoDark("/img/acme-dark.png");
		assertThat(properties.darkLogo()).isEqualTo("/img/acme-dark.png");
	}

	@Test
	void nothingIsDrawnWhenNoLogoIsSet() {
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		assertThat(properties.darkLogo()).isNull();
		assertThat(properties.servedPaths()).isEmpty();
	}

	@Test
	void servedPathsKeepTheLogosTheApplicationServesItself() {
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		properties.setLogo("/img/acme.png");
		properties.setLogoDark("/img/acme-dark.png");
		assertThat(properties.servedPaths()).containsExactly("/img/acme.png", "/img/acme-dark.png");
	}

	@Test
	void servedPathsLeaveOutTheLogosServedElsewhere() {
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		properties.setLogo("https://cdn.example.com/acme.png");
		properties.setLogoDark("/img/acme-dark.png");
		assertThat(properties.servedPaths()).containsExactly("/img/acme-dark.png");
	}

	@Test
	void servedPathsLeaveOutAProtocolRelativeUrl() {
		// The browser completes it with the scheme of the page: somebody else serves it.
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		properties.setLogo("//cdn.example.com/acme.png");
		assertThat(properties.servedPaths()).isEmpty();
	}

	@Test
	void servedPathsDropTheQueryString() {
		// The chain matches what the browser asks for, and '?' would read as a wildcard.
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		properties.setLogo("/img/acme.png?v=2");
		assertThat(properties.getLogo()).isEqualTo("/img/acme.png?v=2");
		assertThat(properties.servedPaths()).containsExactly("/img/acme.png");
	}

	@Test
	void servedPathsOpenNothingForADarkLogoWithoutALightOne() {
		// The pages draw the lockup then: nothing asks for the file.
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		properties.setLogoDark("/img/acme-dark.png");
		assertThat(properties.darkLogo()).isNull();
		assertThat(properties.servedPaths()).isEmpty();
	}

	@Test
	void aBlankValueKeepsTheLockup() {
		// The binder hands an empty environment variable over as "", not as null.
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		properties.setLogo("  ");
		properties.setLogoDark("");
		properties.setName("");
		assertThat(properties.getLogo()).isNull();
		assertThat(properties.getLogoDark()).isNull();
		assertThat(properties.getName()).isNull();
	}

	@Test
	void aPathMustStartWithASlash() {
		// Rendered relative to the page otherwise, and served by nothing.
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		assertThatIllegalArgumentException().isThrownBy(() -> properties.setLogo("img/acme.png"))
			.withMessageContaining("img/acme.png");
	}

	@Test
	void aPathCannotBeAPattern() {
		// The chain would open every file it matches, the whole console for '/**'.
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		assertThatIllegalArgumentException().isThrownBy(() -> properties.setLogo("/**"));
		assertThatIllegalArgumentException().isThrownBy(() -> properties.setLogoDark("/img/*.png"));
	}

	@Test
	void aValueThatIsNeitherAPathNorAUrlIsRefused() {
		GatewayUiBrandingProperties properties = new GatewayUiBrandingProperties();
		assertThatIllegalArgumentException().isThrownBy(() -> properties.setLogo("/img/acme{.png"))
			.withMessageContaining("/img/acme{.png");
	}

}
