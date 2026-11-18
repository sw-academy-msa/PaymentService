package com.msa.payment.global.config.toss;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;

import java.net.http.HttpClient;
import java.time.Duration;

/** 실제 승인 전 키 종류와 목적지를 검증하고 TLS·리다이렉트 금지·시간 제한을 적용합니다. */
@Configuration
@EnableConfigurationProperties(TossProperties.class)
public class TossConfig {
    @Bean
    public HttpClient tossHttpClient(TossProperties p, Environment env) {
        if (p.enabled()) {
            boolean widget =
                    p.secretKey() != null
                            && p.secretKey().startsWith("test_gsk_")
                            && p.clientKey() != null
                            && p.clientKey().startsWith("test_gck_");
            boolean individual =
                    p.secretKey() != null
                            && p.secretKey().startsWith("test_sk_")
                            && p.clientKey() != null
                            && p.clientKey().startsWith("test_ck_");
            if (!widget && !individual)
                throw new IllegalStateException("동일 종류의 토스 테스트 키 설정이 필요합니다. 라이브 키는 허용하지 않습니다.");
        }
        var uri = p.baseUrl();
        boolean productionEndpoint =
                uri != null
                        && "https".equals(uri.getScheme())
                        && "api.tosspayments.com".equals(uri.getHost())
                        && (uri.getPort() == -1 || uri.getPort() == 443);
        boolean isolatedFixture =
                p.allowInsecureLocal()
                        && env.acceptsProfiles(Profiles.of("test & !prod"))
                        && uri != null
                        && "http".equals(uri.getScheme())
                        && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost());
        if (!(productionEndpoint || isolatedFixture)
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalStateException("토스 API 목적지 설정이 올바르지 않습니다.");
        if (p.requestTimeout() == null
                || p.requestTimeout().compareTo(Duration.ofMillis(100)) < 0
                || p.requestTimeout().compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalStateException("토스 호출 제한시간은 100ms~10초 범위여야 합니다.");
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
