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

package ch.nexsol.gateway.validation;

import org.springframework.http.MediaType;

/**
 * Which media types this plugin can hold against a schema.
 */
public final class ValidationMediaTypes {

	/**
	 * Returns whether a body of this media type can be validated against a JSON schema.
	 * Everything else &mdash; a multipart upload, a binary stream, an image, a form
	 * submission &mdash; carries nothing a JSON schema could be applied to, which is what
	 * lets the filter decide not to buffer it at all.
	 * @param contentType the media type of the body, may be {@code null}
	 * @return {@code true} for {@code application/json} and any {@code +json} suffixed
	 * type
	 */
	public static boolean isJson(MediaType contentType) {
		if (contentType == null) {
			return false;
		}
		String subtype = contentType.getSubtype();
		return subtype.equals("json") || subtype.endsWith("+json");
	}

	private ValidationMediaTypes() {

	}

}
