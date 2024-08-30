CREATE TYPE "JSONB" AS json;

CREATE TABLE route (
    id SERIAL PRIMARY KEY,
    route_id VARCHAR(255) NOT NULL UNIQUE,
    uri VARCHAR(255) NOT NULL,
    route_order INT
);

CREATE TABLE predicate (
    id SERIAL PRIMARY KEY,
    route_ref_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    args TEXT,
    FOREIGN KEY (route_ref_id) REFERENCES route(id) ON DELETE CASCADE
);

CREATE TABLE filter (
    id SERIAL PRIMARY KEY,
    route_ref_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    args TEXT,
    FOREIGN KEY (route_ref_id) REFERENCES route(id) ON DELETE CASCADE
);