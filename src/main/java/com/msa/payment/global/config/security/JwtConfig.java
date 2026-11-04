package com.msa.payment.global.config.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Account/Gateway와 동일한 JWT 계약을 서비스에서도 재검증합니다. 키 조회는 Nimbus가 캐시합니다. */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {
    @Bean
    public JwtDecoder jwtDecoder(AuthProperties properties, Clock clock) {
        // issuer discovery 없이 시작하며 실제 서명 검증 시 JWKS 공개키만 조회합니다.
        var decoder =
                NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .build();
        decoder.setJwtValidator(validators(properties, clock));
        return decoder;
    }

    // 서명 검증과 독립적으로 계약을 테스트할 수 있도록 claim 검증기를 분리합니다.
    static OAuth2TokenValidator<Jwt> validators(AuthProperties properties, Clock clock) {
        var timestamp = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamp.setClock(clock);
        OAuth2TokenValidator<Jwt> audience =
                jwt ->
                        jwt.getAudience().contains(properties.audience())
                                ? OAuth2TokenValidatorResult.success()
                                : invalid();
        return new DelegatingOAuth2TokenValidator<>(
                timestamp,
                new JwtIssuerValidator(properties.issuer()),
                audience,
                new JwtClaimValidator<Instant>("exp", Objects::nonNull),
                new JwtClaimValidator<String>("token_use", "access"::equals),
                new JwtClaimValidator<String>("sub", JwtConfig::isAccountId),
                jwt ->
                        jwt.getClaimAsString("principal_type") == null
                                        || "user".equals(jwt.getClaimAsString("principal_type"))
                                ? OAuth2TokenValidatorResult.success()
                                : invalid());
    }

    // 사용자 식별은 검증된 sub만 사용하므로 UUID가 아닌 subject는 인증 단계에서 거절합니다.
    private static boolean isAccountId(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "JWT 계약이 올바르지 않습니다.", null));
    }
}
