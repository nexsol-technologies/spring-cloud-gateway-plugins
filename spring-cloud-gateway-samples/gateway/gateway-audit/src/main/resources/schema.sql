CREATE TABLE IF NOT EXISTS audit_event (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_timestamp TIMESTAMP NOT NULL,
    attributes      TEXT NOT NULL
);
