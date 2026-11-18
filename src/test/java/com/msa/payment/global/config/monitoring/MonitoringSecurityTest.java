package com.msa.payment.global.config.monitoring;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** DB나 Account 호출 없이 실제 메트릭 전용 SecurityFilterChain의 격리를 확인합니다. */
@SpringJUnitConfig(MonitoringSecurityTest.TestConfig.class)
@WebAppConfiguration
@TestPropertySource(properties = "monitoring.scrape-token=synthetic-monitoring-token-for-test-only")
class MonitoringSecurityTest {
    @Autowired WebApplicationContext context;

    @org.springframework.boot.test.context.TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({MonitoringSecurityConfig.class, Endpoint.class})
    static class TestConfig {}

    @RestController
    static class Endpoint {
        @GetMapping("/actuator/prometheus")
        String metrics() {
            return "synthetic_metric 1";
        }
    }

    @Test
    void scrapeChainRequiresDedicatedCredential() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer buyer-jwt"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/actuator/prometheus")
                                .header(
                                        "Authorization",
                                        "Bearer synthetic-monitoring-token-for-test-only"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/actuator/prometheus")
                                .header(
                                        "Authorization",
                                        "Bearer synthetic-monitoring-token-for-test-only"))
                .andExpect(status().isForbidden());
    }
}
