package com.msa.payment.global.config.membership;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** 빌링 활성 시 테스트 개별 키와 API 목적지를 검증하며 기존 결제 클라이언트는 유지합니다. */
@Configuration
@EnableConfigurationProperties({BillingProperties.class, MembershipCryptoProperties.class})
public class BillingConfig {
    @Bean
    @ConditionalOnProperty(name = "membership.billing.enabled", havingValue = "true")
    public HttpClient billingHttpClient(BillingProperties p, Environment env) {
        if (p.clientKey() == null
                || !p.clientKey().startsWith("test_ck_")
                || p.secretKey() == null
                || !p.secretKey().startsWith("test_sk_"))
            throw new IllegalStateException("빌링 API 개별 테스트 키가 필요합니다. 라이브 키는 허용하지 않습니다.");
        var uri = p.baseUrl();
        boolean provider =
                "https".equals(uri.getScheme())
                        && "api.tosspayments.com".equals(uri.getHost())
                        && (uri.getPort() == -1 || uri.getPort() == 443);
        boolean fixture =
                p.allowInsecureLocal()
                        && env.acceptsProfiles(Profiles.of("test & !prod"))
                        && "http".equals(uri.getScheme())
                        && java.util.Set.of("127.0.0.1", "localhost").contains(uri.getHost());
        if (!(provider || fixture)
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalStateException("빌링 API 목적지가 올바르지 않습니다.");
        if (p.requestTimeout().compareTo(Duration.ofSeconds(60)) < 0
                || p.requestTimeout().compareTo(Duration.ofSeconds(90)) > 0)
            throw new IllegalStateException("빌링 승인 제한시간은 60~90초여야 합니다.");
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "membership.billing.enabled", havingValue = "true")
    public SecretKey membershipDataKey(MembershipCryptoProperties p) {
        try {
            byte[] bytes = Base64.getDecoder().decode(p.dataKeyV1());
            if (bytes.length != 32) throw new IllegalArgumentException();
            var key = new SecretKeySpec(bytes, "AES");
            java.util.Arrays.fill(bytes, (byte) 0);
            return key;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("멤버십 전용 32바이트 암호화 키가 필요합니다.");
        }
    }
}
