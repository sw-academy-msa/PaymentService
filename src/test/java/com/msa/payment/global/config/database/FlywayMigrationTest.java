package com.msa.payment.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 빈 PostgreSQL에 Flyway SQL만 적용한 뒤 운영과 같은 validate로 엔티티와 테이블이 일치하는지 검사합니다. */
@SpringBootTest(
        properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
class FlywayMigrationTest {
    // 스키마도 Flyway가 만들도록 초기화 스크립트 없이 빈 DB로 시작합니다.
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired Flyway flyway;

    // 엔티티를 바꾸고 SQL 추가를 빠뜨리면 컨텍스트 기동 단계의 validate에서 실패합니다.
    @Test
    void migrationsCreateSchemaMatchingEntities() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
    }
}
