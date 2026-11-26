CREATE TABLE IF NOT EXISTS comatching_user.feature_operation (
    feature_key VARCHAR(32) PRIMARY KEY,
    open_for_use BOOLEAN NOT NULL,
    message VARCHAR(240) NOT NULL,
    changed_by BIGINT NULL,
    changed_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL
);
