CREATE TABLE IF NOT EXISTS route (
    id SERIAL PRIMARY KEY,
    route_id VARCHAR(255) NOT NULL UNIQUE,
    uri VARCHAR(255) NOT NULL,
    route_order INT
);

CREATE TABLE IF NOT EXISTS predicate (
    id SERIAL PRIMARY KEY,
    route_ref_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    args TEXT,
    FOREIGN KEY (route_ref_id) REFERENCES route(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS filter (
    id SERIAL PRIMARY KEY,
    route_ref_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    args TEXT,
    FOREIGN KEY (route_ref_id) REFERENCES route(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS predicate_route_ref_id_idx ON predicate(route_ref_id);
CREATE INDEX IF NOT EXISTS filter_route_ref_id_idx ON filter(route_ref_id);
CREATE INDEX IF NOT EXISTS route_id_idx ON route(route_id);
