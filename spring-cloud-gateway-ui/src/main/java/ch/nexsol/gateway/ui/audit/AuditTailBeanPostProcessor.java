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

package ch.nexsol.gateway.ui.audit;

import ch.nexsol.gateway.audit.AuditEventPublisher;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps whichever {@link AuditEventPublisher} the application ended up with in a
 * {@link RecordingAuditEventPublisher}, so the audit view sees the events on their way to
 * the configured backend.
 * <p>
 * The buffer is resolved lazily: a bean post-processor is created very early, before the
 * beans it will decorate exist.
 */
public class AuditTailBeanPostProcessor implements BeanPostProcessor {

	private final ObjectProvider<AuditTailBuffer> buffer;

	/**
	 * Creates the post-processor.
	 * @param buffer the provider over the tail buffer receiving the recorded events
	 */
	public AuditTailBeanPostProcessor(ObjectProvider<AuditTailBuffer> buffer) {
		this.buffer = buffer;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (!(bean instanceof AuditEventPublisher publisher) || bean instanceof RecordingAuditEventPublisher) {
			return bean;
		}
		AuditTailBuffer tail = this.buffer.getIfAvailable();
		return (tail != null) ? new RecordingAuditEventPublisher(publisher, tail) : bean;
	}

}
