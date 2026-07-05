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

package ch.nexsol.gateway.database.repository;

import java.util.Collection;

import ch.nexsol.gateway.database.entity.FilterEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface FilterRepository extends ReactiveCrudRepository<FilterEntity, Long> {

	Flux<FilterEntity> findByRouteRefId(Long routeId);

	Flux<FilterEntity> findByRouteRefIdIn(Collection<Long> routeIds);

	Mono<Void> deleteByRouteRefId(Long routeId);

}
