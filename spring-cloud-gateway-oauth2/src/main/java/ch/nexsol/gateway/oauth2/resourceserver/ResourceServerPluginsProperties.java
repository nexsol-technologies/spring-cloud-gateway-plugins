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

package ch.nexsol.gateway.oauth2.resourceserver;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the OAuth 2.0 resource server plugins, covering the JSON
 * path granted-authorities mapping and the list of multi-tenant issuers.
 */
@Validated
public class ResourceServerPluginsProperties {

	/**
	 * List of json path for mapping to Spring Security GrantedAuthority
	 */
	@Valid
	private JsonPathGrantedAuthorityProperties grantedAuthoritiesMapping;

	/**
	 * List of multi-tenant
	 */
	private List<@Valid @NotNull OAuth2ResourceServerProperties> multitenant = new ArrayList<>();

	/**
	 * @return the multi-tenant
	 */
	public List<OAuth2ResourceServerProperties> getMultitenant() {
		return this.multitenant;
	}

	/**
	 * @param multitenant the multi-tenant to set
	 */
	public void setMultitenant(List<OAuth2ResourceServerProperties> multitenant) {
		this.multitenant = multitenant;
	}

	/**
	 * @return the list of issuer uri configured
	 */
	public List<String> getIssuerUri() {
		if (this.multitenant != null) {
			return this.multitenant.stream()
				.map((property) -> property.getIssuerUri())
				.map((issuerUri) -> issuerUri.toString())
				.collect(Collectors.toUnmodifiableList());
		}
		return Collections.emptyList();
	}

	/**
	 * @return true if some issuer uri are configured
	 */
	public boolean isMultitenantEnabled() {
		return !this.multitenant.isEmpty();
	}

	/**
	 * Return the JSON path granted-authorities mapping.
	 * @return the granted authorities mapping
	 */
	public JsonPathGrantedAuthorityProperties getGrantedAuthoritiesMapping() {
		return this.grantedAuthoritiesMapping;
	}

	/**
	 * Set the JSON path granted-authorities mapping.
	 * @param grantedAuthoritiesMapping the granted authorities mapping to set
	 */
	public void setGrantedAuthoritiesMapping(JsonPathGrantedAuthorityProperties grantedAuthoritiesMapping) {
		this.grantedAuthoritiesMapping = grantedAuthoritiesMapping;
	}

	/**
	 * Properties describing a single resource server tenant (its id and issuer URI).
	 */
	public static class OAuth2ResourceServerProperties {

		/**
		 * identifier of the tenant
		 */
		@NotEmpty
		private String id;

		/**
		 * the issuer uri of the tenant
		 */
		@NotNull
		private URI issuerUri;

		/**
		 * @return the id
		 */
		public String getId() {
			return this.id;
		}

		/**
		 * @param id the id to set
		 */
		public void setId(String id) {
			this.id = id;
		}

		/**
		 * @return the issuerUri
		 */
		public URI getIssuerUri() {
			return this.issuerUri;
		}

		/**
		 * @param issuerUri the issuerUri to set
		 */
		public void setIssuerUri(URI issuerUri) {
			this.issuerUri = issuerUri;
		}

	}

	/**
	 * Properties holding the list of JSON path expressions used to map JWT claims to
	 * Spring Security granted authorities.
	 */
	public static class JsonPathGrantedAuthorityProperties {

		private List<String> jsonPath = new ArrayList<>();

		/**
		 * Return the configured JSON path expressions.
		 * @return the json paths
		 */
		public List<String> getJsonPath() {
			return this.jsonPath;
		}

		/**
		 * Set the JSON path expressions.
		 * @param jsonPath the json paths to set
		 */
		public void setJsonPath(List<String> jsonPath) {
			this.jsonPath = jsonPath;
		}

		/**
		 * Whether the default mapping should be used because no JSON path is configured.
		 * @return {@code true} when no JSON path is configured
		 */
		public boolean isEnabledDefault() {
			return this.jsonPath == null || this.jsonPath.isEmpty();
		}

	}

}
