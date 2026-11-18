package com.msa.payment.global.config.security;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 외부 토큰 issuer와 내부 키 조회 주소를 구분하며 개인키·JWT 발급 설정을 보유하지 않습니다. */
@Validated
@ConfigurationProperties("payment.auth")
public record AuthProperties(
        @NotBlank String issuer, @NotBlank String audience, @NotBlank String jwkSetUri) {
    /** 프로필을 아는 구성 단계에서 호출하며 운영 키 조회의 평문 통신을 차단합니다. */
    public void validate(org.springframework.core.env.Environment environment) {
        if (!environment.acceptsProfiles(
                        org.springframework.core.env.Profiles.of("(local | test) & !prod"))
                && !"https".equalsIgnoreCase(java.net.URI.create(jwkSetUri).getScheme())) {
            throw new IllegalStateException("로컬·테스트 외의 JWKS 주소는 HTTPS여야 합니다.");
        }
    }
}
