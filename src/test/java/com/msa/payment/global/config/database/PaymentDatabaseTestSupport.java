package com.msa.payment.global.config.database;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;

/** 실제 PostgreSQL·가변 시각으로 동시성/임대/재시작을 검증합니다. 기존 로컬 데이터는 읽지 않습니다. */
public abstract class PaymentDatabaseTestSupport {
    static final PostgreSQLContainer DB =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("payment")
                    .withUsername("payment_test")
                    .withInitScript("db/payment-schema.sql");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @TestConfiguration
    public static class Time {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    public static class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2030-01-01T00:00:00Z");

        public void set(Instant value) {
            now = value;
        }

        public Instant instant() {
            return now;
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
