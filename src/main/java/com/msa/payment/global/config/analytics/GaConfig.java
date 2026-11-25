package com.msa.payment.global.config.analytics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.net.http.HttpClient;
import java.time.Duration;

/** MP는 고정 HTTPS 목적지로만 보내고 테스트 루프백 예외를 운영에서 차단합니다. */
@Configuration
@EnableConfigurationProperties(GaProperties.class)
public class GaConfig {
    @Bean
    public HttpClient gaHttpClient(GaProperties p, Environment env) {
        var u = p.baseUrl();
        boolean production =
                u != null
                        && "https".equals(u.getScheme())
                        && "www.google-analytics.com".equals(u.getHost())
                        && (u.getPort() == -1 || u.getPort() == 443);
        boolean fixture =
                p.allowInsecureLocal()
                        && env.acceptsProfiles(Profiles.of("test & !prod"))
                        && u != null
                        && "http".equals(u.getScheme())
                        && java.util.Set.of("127.0.0.1", "localhost").contains(u.getHost());
        if ((!production && !fixture)
                || u.getUserInfo() != null
                || u.getQuery() != null
                || u.getFragment() != null
                || !(u.getPath().isEmpty() || "/".equals(u.getPath())))
            throw new IllegalStateException("GA 목적지 설정이 올바르지 않습니다.");
        if (p.enabled()
                && (p.measurementId() == null
                        || !p.measurementId().matches("G-[A-Z0-9]+")
                        || p.apiSecret() == null
                        || p.apiSecret().isBlank()))
            throw new IllegalStateException("GA 전송 설정이 필요합니다.");
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    public ThreadPoolTaskScheduler gaWorkerScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ga-worker-");
        return scheduler;
    }
}
