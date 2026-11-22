-- 구버전 데이터의 초기 버전은 0이다. 두 서비스 배포 전에 실행한다.

SET @ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'comatching_user' AND table_name = 'profile' AND column_name = 'profile_version'
), 'SELECT 1', 'ALTER TABLE comatching_user.profile ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0');
PREPARE add_column FROM @ddl;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'comatching_matching' AND table_name = 'matching_candidate' AND column_name = 'last_profile_version'
), 'SELECT 1', 'ALTER TABLE comatching_matching.matching_candidate ADD COLUMN last_profile_version BIGINT NOT NULL DEFAULT 0');
PREPARE add_column FROM @ddl;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;
