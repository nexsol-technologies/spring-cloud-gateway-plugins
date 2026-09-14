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

package ch.nexsol.gateway.passivescan.webfilter;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;

/**
 * Response decorator that copies the bytes written downstream into a bounded buffer,
 * without consuming them, so the passive scanners can read the response body. Capture
 * stops once {@code maxBytes} have been collected; the rest streams through untouched.
 */
public class CapturingServerHttpResponse extends ServerHttpResponseDecorator {

	private final int maxBytes;

	private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

	public CapturingServerHttpResponse(ServerHttpResponse delegate, int maxBytes) {
		super(delegate);
		this.maxBytes = maxBytes;
	}

	@Override
	public reactor.core.publisher.Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
		return super.writeWith(Flux.from(body).doOnNext(this::capture));
	}

	private void capture(DataBuffer buffer) {
		int remaining = this.maxBytes - this.captured.size();
		if (remaining <= 0) {
			return;
		}
		int length = Math.min(buffer.readableByteCount(), remaining);
		if (length <= 0) {
			return;
		}
		byte[] bytes = new byte[length];
		buffer.toByteBuffer(buffer.readPosition(), ByteBuffer.wrap(bytes), 0, length);
		this.captured.write(bytes, 0, length);
	}

	/**
	 * The captured body decoded as UTF-8, empty when nothing was written.
	 * @return the captured body
	 */
	public String capturedBody() {
		return this.captured.toString(StandardCharsets.UTF_8);
	}

}
