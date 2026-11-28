-- NULL은 신고자가 현재 접수/처리 상태를 아직 읽지 않았음을 뜻한다.
SET @ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'comatching_user' AND table_name = 'member_report' AND column_name = 'reporter_read_at'
), 'SELECT 1', 'ALTER TABLE comatching_user.member_report ADD COLUMN reporter_read_at DATETIME(6) NULL');
PREPARE add_reporter_read_at FROM @ddl;
EXECUTE add_reporter_read_at;
DEALLOCATE PREPARE add_reporter_read_at;
