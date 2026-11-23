-- user-service 배포 전에 적용한다. 기존 회원도 새로 로그인할 때 버전 0부터 시작한다.
SET @ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'comatching_user' AND table_name = 'members' AND column_name = 'auth_version'
), 'SELECT 1', 'ALTER TABLE comatching_user.members ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0');
PREPARE add_auth_version FROM @ddl;
EXECUTE add_auth_version;
DEALLOCATE PREPARE add_auth_version;
