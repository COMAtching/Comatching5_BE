CREATE TABLE IF NOT EXISTS comatching_user.member_report_evidence (
    report_id BIGINT NOT NULL PRIMARY KEY,
    snapshot JSON NOT NULL,
    CONSTRAINT fk_report_evidence_report FOREIGN KEY (report_id) REFERENCES comatching_user.member_report(id) ON DELETE CASCADE
);
