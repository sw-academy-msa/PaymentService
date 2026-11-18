package com.msa.payment.global.config.openapi;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.msa.payment.global.config.database.PaymentDatabaseTestSupport;

import io.swagger.v3.oas.models.OpenAPI;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** 실제 생성 문서와 추적 중인 계약을 비교하며 운영의 문서 접근 정책은 변경하지 않습니다. */
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(OpenApiContractTest.DocumentAccess.class)
class OpenApiContractTest extends PaymentDatabaseTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired OpenAPI contract;

    @TestConfiguration
    static class DocumentAccess {
        // 문서 추출 테스트에만 한정하며 나머지 경로는 실제 보안 체인을 사용합니다.
        @Bean
        @Order(0)
        SecurityFilterChain documentation(HttpSecurity http) throws Exception {
            return http.securityMatcher("/v3/api-docs")
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
    }

    @Test
    void generatedContractMatchesSnapshotAndCanonicalKoreanDescription() throws Exception {
        String generated =
                mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        Path path = Path.of("build/generated/payment.openapi.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, generated, StandardCharsets.UTF_8);
        var document = mapper.readTree(generated);
        String description = document.path("info").path("description").asText();
        assertThat(description).isEqualTo(contract.getInfo().getDescription());
        assertThat(description.codePoints().anyMatch(c -> Character.isISOControl(c))).isFalse();
        assertThat(document.path("servers").get(0).path("url").asText())
                .isEqualTo("http://localhost:8000");
        assertThat(document.path("servers").get(1).path("url").asText())
                .isEqualTo("http://localhost:8084");
        var content =
                document.path("paths")
                        .path("/api/v1/payments/client-configuration")
                        .path("get")
                        .path("responses")
                        .path("200")
                        .path("content");
        assertThat(content.has("application/json")).isTrue();
        assertThat(content.has("*/*")).isFalse();
        assertThat(document)
                .isEqualTo(
                        mapper.readTree(Files.readString(Path.of("openapi/payment.openapi.json"))));
    }

    @Test
    void unsupportedAcceptReturnsJsonError() throws Exception {
        mvc.perform(
                        get("/api/v1/payments/client-configuration")
                                .with(
                                        jwt().authorities(
                                                        new org.springframework.security.core
                                                                .authority.SimpleGrantedAuthority(
                                                                "ROLE_BUYER")))
                                .accept("application/xml"))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("COMMON_NOT_ACCEPTABLE"));
    }
}
