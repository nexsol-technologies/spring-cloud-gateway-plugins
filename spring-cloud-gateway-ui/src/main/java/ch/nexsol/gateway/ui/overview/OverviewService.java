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

package ch.nexsol.gateway.ui.overview;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

/**
 * Gathers the figures shown on the home page from every {@link OverviewContribution}
 * present in the context, so the overview covers exactly the views the application
 * enabled.
 * <p>
 * A contribution that fails is dropped rather than failing the page: the home page is the
 * landing view and must render even when a source of figures is momentarily unavailable.
 */
public class OverviewService {

	private static final Logger LOG = LoggerFactory.getLogger(OverviewService.class);

	private final ObjectProvider<OverviewContribution> contributions;

	private final ApplicationContext applicationContext;

	/**
	 * Creates the service over every contributed source of figures.
	 * @param contributions the provider over every {@link OverviewContribution} bean
	 * @param applicationContext the context the gateway uptime is read from
	 */
	public OverviewService(ObjectProvider<OverviewContribution> contributions, ApplicationContext applicationContext) {
		this.contributions = contributions;
		this.applicationContext = applicationContext;
	}

	/**
	 * Reads the current figures, ordered by {@link OverviewStat#order()}.
	 * @return the figures to show, empty when nothing contributes any
	 */
	public Mono<List<OverviewStat>> stats() {
		return Flux.fromIterable(this.contributions.orderedStream().toList())
			.concatMap(OverviewService::readSafely)
			.sort(Comparator.comparingInt(OverviewStat::order))
			.collectList();
	}

	/**
	 * Returns how long the gateway has been up, ready for display.
	 * @return the elapsed time since the application context started
	 */
	public String uptimeText() {
		return format(Duration.between(Instant.ofEpochMilli(this.applicationContext.getStartupDate()), Instant.now()));
	}

	/**
	 * Renders a duration with its two most significant units, so an uptime reads as
	 * {@code 3d 4h} rather than as a count of seconds.
	 * @param uptime the duration to render
	 * @return the rendered duration
	 */
	static String format(Duration uptime) {
		long days = uptime.toDays();
		if (days > 0) {
			return days + "d " + uptime.toHoursPart() + "h";
		}
		if (uptime.toHours() > 0) {
			return uptime.toHours() + "h " + uptime.toMinutesPart() + "m";
		}
		if (uptime.toMinutes() > 0) {
			return uptime.toMinutes() + "m " + uptime.toSecondsPart() + "s";
		}
		return Math.max(uptime.toSeconds(), 0) + "s";
	}

	private static Flux<OverviewStat> readSafely(OverviewContribution contribution) {
		return contribution.stats().onErrorResume((ex) -> {
			LOG.warn("Overview contribution {} could not be read", contribution.getClass().getSimpleName(), ex);
			return Flux.empty();
		});
	}

}
