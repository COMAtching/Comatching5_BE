package com.comatching.matching.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 실제 MySQL 컨테이너를 띄우고 데이터소스를 그쪽으로 돌린다.
 *
 * == 왜 H2 가 아닌가 ==
 * 후보 조회가 네이티브 MySQL SQL 이다. 조건이 요청마다 켜졌다 꺼졌다 하고 점수식의
 * 항 개수도 변해서 정적 JPQL 로는 표현이 안 되기 때문이다. 그 SQL 은
 * LOCATE, RAND(), 파생 테이블, GROUP BY 결과 위에서의 집계 정렬, bit 컬럼 비교처럼
 * 방언에 민감한 문법을 쓴다. H2 를 MySQL 모드로 돌려도 이것들의 동작이 미묘하게 다르다.
 * 그러면 '테스트는 통과하는데 운영에서 다르게 도는' 최악의 상태가 된다.
 *
 * 로직을 DB 로 내린 대가다. 검증도 DB 에서 해야 한다.
 *
 * == 컨테이너는 한 번만 뜬다 ==
 * static 필드라 이 클래스를 상속한 모든 테스트가 같은 컨테이너를 공유한다.
 * withReuse 는 켜지 않았다. 로컬에 남은 컨테이너가 다음 실행에 영향을 주면
 * '왜 여기선 되는데 CI 에선 안 되지'가 시작된다.
 */
public abstract class MySqlContainerSupport {

	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
		.withDatabaseName("comatching_matching_test")
		.withCommand(
			"--character-set-server=utf8mb4",
			"--collation-server=utf8mb4_unicode_ci");

	static {
		MYSQL.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
		// 인덱스 선언까지 실제로 만들어져야 한다. 옵티마이저가 어떤 인덱스를 고르느냐가
		// 이 쿼리 성능의 핵심이었고, 그건 스키마가 운영과 같을 때만 재현된다.
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}
}
