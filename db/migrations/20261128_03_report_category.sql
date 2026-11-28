-- 기존 신고는 분류를 추정하지 않고 NULL로 둔다.
SET @ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'comatching_user' AND table_name = 'member_report' AND column_name = 'category'
), 'SELECT 1', 'ALTER TABLE comatching_user.member_report ADD COLUMN category VARCHAR(32) NULL');
PREPARE add_report_category FROM @ddl;
EXECUTE add_report_category;
DEALLOCATE PREPARE add_report_category;
