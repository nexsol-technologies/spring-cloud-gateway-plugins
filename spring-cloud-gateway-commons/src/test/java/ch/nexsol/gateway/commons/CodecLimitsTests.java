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

import org.junit.jupiter.api.Test;

import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CodecLimits}.
 */
class CodecLimitsTests {

	@Test
	void returnsTheSizeInBytes() {
		assertThat(CodecLimits.maxInMemoryBytes(DataSize.ofMegabytes(2))).isEqualTo(2 * 1024 * 1024);
	}

	@Test
	void capsASizeAboveTwoGibibytesInsteadOfOverflowingIntoANegativeCeiling() {
		assertThat(CodecLimits.maxInMemoryBytes(DataSize.ofGigabytes(4))).isEqualTo(Integer.MAX_VALUE);
	}

	@Test
	void keepsASizeOfZeroAsIs() {
		assertThat(CodecLimits.maxInMemoryBytes(DataSize.ofBytes(0))).isZero();
	}

}
