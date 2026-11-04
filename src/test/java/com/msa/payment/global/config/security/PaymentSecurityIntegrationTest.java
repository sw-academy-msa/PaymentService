package com.msa.payment.global.config.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 개발 DB와 분리된 PostgreSQL에서 실행·권한 경계를 검증합니다. JWT 서명 검증은 별도 테스트입니다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentSecurityIntegrationTest {
    static final JwtTestIssuer ISSUER = new JwtTestIssuer();
    static final PostgreSQLContainer DB =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("payment")
                    .withUsername("payment_test")
                    .withInitScript("db/payment-schema.sql");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry props) {
        props.add("payment.auth.jwk-set-uri", ISSUER::uri);
        props.add("spring.datasource.url", DB::getJdbcUrl);
        props.add("spring.datasource.username", DB::getUsername);
        props.add("spring.datasource.password", DB::getPassword);
    }

    @org.junit.jupiter.api.AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    /** 도메인 예외도 기존 503 코드·메시지와 캐시 금지 헤더로 변환되어야 합니다. */
    @Test
    void disabledPaymentConfigurationKeepsExistingErrorResponse() throws Exception {
        mvc.perform(
                        get("/api/v1/payments/client-configuration")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMON_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("결제 서비스를 사용할 수 없습니다."));
    }

    @Test
    void healthIsPublicButPaymentNeedsBearer() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/v1/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void buyerReachesUnimplementedPathButOtherRolesCannot() throws Exception {
        mvc.perform(
                        get("/api/v1/payments")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/payments")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SELLER"))))
                .andExpect(status().isForbidden());
    }

    /** 메서드가 달라도 BUYER 권한 경계가 유지됩니다. */
    @Test
    void sellerGetIsForbiddenAndBuyerPostReachesUnimplementedPath() throws Exception {
        mvc.perform(
                        get("/api/v1/payments")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SELLER"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/payments")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void unimplementedInternalAndManagementPathsAreClosed() throws Exception {
        for (var path :
                java.util.List.of(
                        "/internal/v1/payments",
                        "/actuator/env",
                        "/api/v1/admin/payments",
                        "/v3/api-docs")) {
            mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))))
                    .andExpect(status().isForbidden());
        }
    }

    /** 실제 서명 토큰이 디코더와 roles 변환기를 거쳐 BUYER 인가를 통과해야 합니다. */
    @Test
    void signedRolesAreConvertedByConfiguredSecurityChain() throws Exception {
        for (String role : java.util.List.of("BUYER", "SELLER")) {
            var now = java.time.Instant.now();
            var claims =
                    org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                            .issuer(environment.getRequiredProperty("payment.auth.issuer"))
                            .audience(
                                    java.util.List.of(
                                            environment.getRequiredProperty(
                                                    "payment.auth.audience")))
                            .subject(java.util.UUID.randomUUID().toString())
                            .issuedAt(now.minusSeconds(1))
                            .expiresAt(now.plusSeconds(300))
                            .claim("token_use", "access")
                            .claim("roles", java.util.List.of(role))
                            .build();
            var result =
                    mvc.perform(
                            get("/api/v1/payments")
                                    .header("Authorization", "Bearer " + ISSUER.token(claims)));
            result.andExpect(role.equals("BUYER") ? status().isNotFound() : status().isForbidden());
        }
    }

    /** 테스트 프로필은 로컬 비밀 파일을 읽지 않고 격리 설정으로 시작합니다. */
    @Test
    void testProfileDoesNotImportLocalEnv() {
        assertThat(
                        environment.getPropertySources().stream()
                                .map(org.springframework.core.env.PropertySource::getName))
                .noneMatch(name -> name.contains(".env"));
    }

    @Test
    void ownSchemaDdlAndDmlAreAvailableWithoutCreatingBusinessTables() {
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .isEqualTo("payment");
        tx.executeWithoutResult(
                s -> {
                    jdbc.execute("create table payment.permission_probe (id integer primary key)");
                    jdbc.update("insert into payment.permission_probe values (1)");
                    assertThat(
                                    jdbc.queryForObject(
                                            "select count(*) from payment.permission_probe",
                                            Integer.class))
                            .isEqualTo(1);
                    s.setRollbackOnly();
                });
        assertThat(
                        jdbc.queryForObject(
                                "select to_regclass('payment.permission_probe') is null",
                                Boolean.class))
                .isTrue();
    }
}
