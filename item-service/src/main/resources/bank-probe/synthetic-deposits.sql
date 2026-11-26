CREATE TABLE IF NOT EXISTS synthetic_probe_deposit (
    stream_id CHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL,
    amount_krw BIGINT NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    description VARCHAR(80) NOT NULL,
    PRIMARY KEY (stream_id, sequence_no)
);
