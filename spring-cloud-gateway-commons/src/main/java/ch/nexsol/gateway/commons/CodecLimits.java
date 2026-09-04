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

package ch.nexsol.gateway.commons;

import org.springframework.util.unit.DataSize;

/**
 * Turns a configured size into the buffering ceiling the reactive stack takes, be it
 * {@code maxInMemorySize} on a codec or the maximum byte count of
 * {@code DataBufferUtils.join}.
 */
public final class CodecLimits {

	private CodecLimits() {
	}

	/**
	 * The given size as the number of bytes those ceilings are expressed in.
	 * <p>
	 * Clamped rather than cast: {@link DataSize} counts in {@code long}, the ceilings in
	 * {@code int}, and a configured size above 2&nbsp;GiB overflows into a negative one
	 * &mdash; which the limit check reads as "already exceeded" and turns into a gateway
	 * that rejects every single body it was configured to accept.
	 * @param size the configured size
	 * @return the ceiling in bytes, capped at {@link Integer#MAX_VALUE}
	 */
	public static int maxInMemoryBytes(DataSize size) {
		return (int) Math.min(size.toBytes(), Integer.MAX_VALUE);
	}

}
