package com.msa.payment.global.config.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** 수집 토큰을 사용자 JWT로 디코딩하지 않으며 업무 API의 역할, 소유권 검증은 기존 체인이 담당합니다. */
@Configuration(proxyBeanMethods = false)
public class MonitoringSecurityConfig {
    @Bean
    @Order(-100)
    public SecurityFilterChain monitoringSecurity(
            HttpSecurity http, @Value("${monitoring.scrape-token:}") String token)
            throws Exception {
        return http.securityMatcher("/actuator/prometheus", "/actuator/prometheus/**")
                .csrf(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.anyRequest()
                                        .access(
                                                (authentication, context) ->
                                                        new AuthorizationDecision(
                                                                MonitoringAccess.allowed(
                                                                        token,
                                                                        context.getRequest()
                                                                                .getHeader(
                                                                                        "Authorization"),
                                                                        context.getRequest()
                                                                                .getMethod()))))
                .build();
    }
}
