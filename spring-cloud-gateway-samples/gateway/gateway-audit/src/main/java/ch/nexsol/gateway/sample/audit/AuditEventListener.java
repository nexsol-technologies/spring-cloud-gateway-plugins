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

package ch.nexsol.gateway.sample.audit;

import java.util.Map;

import ch.nexsol.gateway.audit.AuditApplicationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the events the default publisher republishes as Spring application events.
 * This is the escape hatch a gateway uses to forward the events somewhere no provider
 * module covers, and the sample shows it by logging what a backend would receive.
 * <p>
 * It listens whatever the selected provider: the providers replace the publisher, not the
 * event, so under {@code --spring.profiles.active=redis} the events reach Redis and this
 * listener stays silent.
 */
@Component
public class AuditEventListener {

	private static final Logger LOG = LoggerFactory.getLogger(AuditEventListener.class);

	@EventListener
	void on(AuditApplicationEvent event) {
		Map<String, String> attributes = event.getAuditEvent().attributes();
		LOG.info("audit {}", attributes);
	}

}
