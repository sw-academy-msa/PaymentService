package com.msa.payment.global.config.security;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.*;
import java.util.*;

/** 테스트 전용 메모리 키로 실제 서명과 claim 계약을 검증하며 Account 키 파일을 읽지 않습니다. */
class JwtConfigTest {
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private static final AuthProperties PROPERTIES =
            new AuthProperties("https://account.test", "stockdrop-api", "http://localhost:1/jwks");
    private static RSAKey signingKey;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void prepare() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-only").generate();
        decoder =
                NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey())
                        .signatureAlgorithm(SignatureAlgorithm.RS256)
                        .build();
        decoder.setJwtValidator(JwtConfig.validators(PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void acceptsAccountAccessToken() {
        assertThat(decoder.decode(token(claims(), signingKey)).getClaimAsStringList("roles"))
                .containsExactly("BUYER", "SELLER");
    }

    @Test
    void rejectsWrongIssuerAudienceAndTokenUse() {
        for (var changed :
                List.of(
                        claims().issuer("https://other.test"),
                        claims().audience(List.of("other")),
                        claims().claim("token_use", "refresh"))) {
            assertThatThrownBy(() -> decoder.decode(token(changed, signingKey)))
                    .isInstanceOf(JwtException.class);
        }
    }

    @Test
    void rejectsExpiredAndNotYetValidTokens() {
        for (var changed :
                List.of(
                        claims().issuedAt(NOW.minusSeconds(120)).expiresAt(NOW.minusSeconds(60)),
                        claims().notBefore(NOW.plusSeconds(60)))) {
            assertThatThrownBy(() -> decoder.decode(token(changed, signingKey)))
                    .isInstanceOf(JwtException.class);
        }
    }

    @Test
    void rejectsMissingExpiryAndInvalidSubject() {
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        token(
                                                claims().claims(values -> values.remove("exp")),
                                                signingKey)))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        token(claims().subject("not-an-account-id"), signingKey)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsDifferentSigningKey() throws Exception {
        var otherKey = new RSAKeyGenerator(2048).keyID("other-test-only").generate();
        assertThatThrownBy(() -> decoder.decode(token(claims(), otherKey)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void decoderCreationDoesNotContactAccount() {
        assertThat(
                        new JwtConfig()
                                .jwtDecoder(
                                        PROPERTIES,
                                        Clock.systemUTC(),
                                        new org.springframework.mock.env.MockEnvironment()
                                                .withProperty("spring.profiles.active", "test")))
                .isNotNull();
    }

    @Test
    void serviceJwtCannotAuthenticatePublicPaymentApi() {
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        token(
                                                claims().claim("principal_type", "service"),
                                                signingKey)))
                .isInstanceOf(JwtException.class);
        assertThat(decoder.decode(token(claims().claim("principal_type", "user"), signingKey)))
                .isNotNull();
    }

    /** 구성 빈의 실제 JWKS 디코더에도 claim 검증기가 연결되어 있어야 합니다. */
    @Test
    void configuredDecoderValidatesClaimsAfterVerifyingRealSignature() {
        try (var issuer = new JwtTestIssuer()) {
            var environment = new org.springframework.mock.env.MockEnvironment();
            environment.setActiveProfiles("test");
            var properties =
                    new AuthProperties(PROPERTIES.issuer(), PROPERTIES.audience(), issuer.uri());
            var configured =
                    new JwtConfig()
                            .jwtDecoder(properties, Clock.fixed(NOW, ZoneOffset.UTC), environment);
            assertThat(configured.decode(issuer.token(claims().build()))).isNotNull();
            for (var invalid :
                    List.of(
                            claims().issuer("https://wrong.test"),
                            claims().audience(List.of("wrong")),
                            claims().claim("token_use", "refresh"),
                            claims().claims(values -> values.remove("aud")))) {
                assertThatThrownBy(() -> configured.decode(issuer.token(invalid.build())))
                        .isInstanceOf(JwtException.class);
            }
        }
    }

    /** 로컬·테스트만 HTTP를 허용하고 프로필이 없거나 운영이면 HTTPS를 요구합니다. */
    @Test
    void startupRequiresHttpsOutsideLocalAndTest() {
        for (String profile : List.of("local", "test")) {
            var env = new org.springframework.mock.env.MockEnvironment();
            env.setActiveProfiles(profile);
            assertThat(new JwtConfig().jwtDecoder(PROPERTIES, Clock.systemUTC(), env)).isNotNull();
        }
        for (String profile : List.of("prod", "staging", "default")) {
            var env = new org.springframework.mock.env.MockEnvironment();
            env.setActiveProfiles(profile);
            assertThatThrownBy(() -> new JwtConfig().jwtDecoder(PROPERTIES, Clock.systemUTC(), env))
                    .isInstanceOf(IllegalStateException.class);
            var secure =
                    new AuthProperties(
                            PROPERTIES.issuer(),
                            PROPERTIES.audience(),
                            "https://account.test/jwks");
            assertThat(new JwtConfig().jwtDecoder(secure, Clock.systemUTC(), env)).isNotNull();
        }
    }

    private static JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder()
                .issuer(PROPERTIES.issuer())
                .audience(List.of(PROPERTIES.audience()))
                .subject("00000000-0000-0000-0000-000000000001")
                .issuedAt(NOW.minusSeconds(1))
                .expiresAt(NOW.plusSeconds(300))
                .claim("token_use", "access")
                .claim("roles", List.of("BUYER", "SELLER"));
    }

    private static String token(JwtClaimsSet.Builder claims, RSAKey key) {
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256)
                                        .keyId(key.getKeyID())
                                        .build(),
                                claims.build()))
                .getTokenValue();
    }
}
