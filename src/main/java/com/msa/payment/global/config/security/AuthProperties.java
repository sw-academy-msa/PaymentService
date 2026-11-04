package com.msa.payment.global.config.security;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 외부 토큰 issuer와 내부 키 조회 주소를 구분하며 개인키·JWT 발급 설정을 보유하지 않습니다. */
@Validated
@ConfigurationProperties("payment.auth")
public record AuthProperties(
        @NotBlank String issuer, @NotBlank String audience, @NotBlank String jwkSetUri) {}
