package com.msa.payment.domain.analytics.client.impl;

import com.msa.payment.domain.analytics.client.InternalHttp;
import com.msa.payment.domain.analytics.client.RemoteUnavailable;
import com.msa.payment.domain.analytics.client.ServiceTokenProvider;
import com.msa.payment.global.config.client.ClientProperties;
import com.msa.payment.global.config.security.AuthProperties;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 기존 Order와 같은 Account 발급 계약을 재사용합니다. 네트워크 실패 시 유효한 캐시만 유지합니다. */
@Component
public class ServiceTokenProviderImpl implements ServiceTokenProvider {
    private static final Set<String> SCOPES = Set.of("ai.personalization.read");
    private final InternalHttp http;
    private final ClientProperties properties;
    private final ObjectMapper mapper;
    private final JwtDecoder decoder;
    private final Clock clock;
    private String cached;
    private Instant expiresAt = Instant.MIN;
    private Instant retryAt = Instant.MIN;

    public ServiceTokenProviderImpl(
            InternalHttp http,
            ClientProperties properties,
            ObjectMapper mapper,
            AuthProperties auth,
            Clock clock) {
        this.http = http;
        this.properties = properties;
        this.mapper = mapper;
        var serviceDecoder =
                NimbusJwtDecoder.withJwkSetUri(auth.jwkSetUri())
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .build();
        var timestamp = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamp.setClock(clock);
        serviceDecoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        timestamp,
                        new JwtIssuerValidator(auth.issuer()),
                        new JwtClaimValidator<List<String>>(
                                "aud", v -> v != null && v.contains(auth.audience())),
                        new JwtClaimValidator<String>("token_use", "access"::equals)));
        this.decoder = serviceDecoder;
        this.clock = clock;
    }

    public synchronized String token() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(expiresAt.minusSeconds(30))) return cached;
        if (now.isBefore(retryAt)) return valid();
        try {
            if (properties.clientSecret() == null
                    || properties.clientSecret().isBlank()
                    || properties.serviceSubject() == null
                    || properties.serviceSubject().isBlank()) throw new RemoteUnavailable();
            String basic =
                    Base64.getEncoder()
                            .encodeToString(
                                    ("payment-service:" + properties.clientSecret())
                                            .getBytes(StandardCharsets.UTF_8));
            // 토큰 발급은 Account 서비스 ID로 보내 LoadBalancer가 인스턴스를 고릅니다. 비밀값은 URL·본문 대신 헤더로만 보냅니다.
            var response =
                    http.send(
                            properties.account(),
                            "POST",
                            "/internal/v1/auth/service-tokens",
                            Map.of(
                                    "Authorization",
                                    "Basic " + basic,
                                    "Content-Type",
                                    "application/x-www-form-urlencoded"),
                            "grant_type=client_credentials&scope=ai.personalization.read");
            if (response.status() != 200) throw new RemoteUnavailable();
            var data = mapper.readTree(response.body());
            String token = data.path("access_token").asText();
            var jwt = decoder.decode(token);
            var scopes = new HashSet<>(Arrays.asList(jwt.getClaimAsString("scope").split(" +")));
            var roles = jwt.getClaimAsStringList("roles");
            if (!"Bearer".equals(data.path("token_type").asText())
                    || !properties.serviceSubject().equals(jwt.getSubject())
                    || !"payment-service".equals(jwt.getClaimAsString("client_id"))
                    || !"service".equals(jwt.getClaimAsString("principal_type"))
                    || !SCOPES.equals(scopes)
                    || roles == null
                    || !roles.isEmpty()
                    || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(clock.instant())
                    || !jwt.getExpiresAt()
                            .equals(
                                    Instant.parse(data.path("expires_at").asText())
                                            .truncatedTo(ChronoUnit.SECONDS))
                    || data.path("expires_in").asLong() <= 0
                    || data.path("expires_in").asLong() > 900
                    || !new HashSet<>(Arrays.asList(data.path("scope").asText().split(" +")))
                            .equals(SCOPES)) throw new RemoteUnavailable();
            cached = token;
            expiresAt = jwt.getExpiresAt();
            retryAt = Instant.MIN;
            return cached;
        } catch (Exception ex) {
            retryAt = clock.instant().plusSeconds(5);
            return valid();
        }
    }

    private String valid() {
        if (cached != null && clock.instant().isBefore(expiresAt)) return cached;
        throw new RemoteUnavailable();
    }

    public synchronized void invalidate() {
        cached = null;
        expiresAt = Instant.MIN;
        retryAt = Instant.MIN;
    }
}
