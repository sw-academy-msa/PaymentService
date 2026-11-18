package com.msa.payment.global.config.security;

import com.msa.payment.global.error.*;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Gateway 헤더를 신뢰하지 않고 JWT를 독립 검증합니다. 미구현 내부·관리 API는 기본 차단합니다. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper, Environment env)
            throws Exception {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return http
                // 쿠키·세션 인증은 하지 않습니다. CORS와 Refresh 계약은 Gateway·Account 책임입니다.
                .csrf(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .logout(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        c ->
                                c.authenticationEntryPoint(
                                                (req, res, ex) ->
                                                        error(
                                                                res,
                                                                CommonErrorCode.UNAUTHORIZED,
                                                                mapper))
                                        .accessDeniedHandler(
                                                (req, res, ex) ->
                                                        error(
                                                                res,
                                                                CommonErrorCode.FORBIDDEN,
                                                                mapper)))
                .authorizeHttpRequests(
                        c -> {
                            c.requestMatchers(
                                            HttpMethod.GET,
                                            "/actuator/health",
                                            "/actuator/health/liveness",
                                            "/actuator/health/readiness")
                                    .permitAll();
                            if (env.acceptsProfiles(Profiles.of("local & !prod"))) {
                                c.requestMatchers(
                                                HttpMethod.GET,
                                                "/v3/api-docs",
                                                "/v3/api-docs/**",
                                                "/swagger-ui.html",
                                                "/swagger-ui/**")
                                        .permitAll();
                            }
                            // 결제 공개 경로는 예약만 합니다. 컨트롤러·소유권 검증은 업무 구현 시 함께 추가합니다.
                            c.requestMatchers(
                                            HttpMethod.GET,
                                            "/api/v1/payments",
                                            "/api/v1/payments/**")
                                    .hasRole("BUYER");
                            c.requestMatchers(
                                            HttpMethod.POST,
                                            "/api/v1/payments",
                                            "/api/v1/payments/**")
                                    .hasRole("BUYER");
                            c.anyRequest().denyAll();
                        })
                .oauth2ResourceServer(
                        c ->
                                c.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                        .authenticationEntryPoint(
                                                (req, res, ex) ->
                                                        error(
                                                                res,
                                                                CommonErrorCode.UNAUTHORIZED,
                                                                mapper)))
                .build();
    }

    // 인증 실패 원문이나 토큰을 응답·로그에 넣지 않고 기존 공통 오류 규격을 유지합니다.
    private void error(HttpServletResponse response, ErrorCode code, ObjectMapper mapper)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (code == CommonErrorCode.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, MDC.get("traceId")));
    }
}
