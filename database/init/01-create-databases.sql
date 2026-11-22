-- 로컬 MySQL의 빈 데이터베이스만 준비한다.
-- 명시적인 테이블 DDL은 후속 init SQL에 두고, 나머지는 local 프로파일의
-- Hibernate ddl-auto=update가 만든다.
CREATE DATABASE IF NOT EXISTS comatching_user
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS comatching_matching
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS comatching_item
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
