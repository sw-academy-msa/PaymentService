package com.msa.payment.global.config.toss;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.time.Duration;

/** 테스트·라이브 혼용과 임의 목적지 전송을 차단하며 설정 출력에 시크릿이 없는지 확인합니다. */
class TossConfigTest {
    TossProperties props(String secret, String client, String url, boolean allow) {
        return new TossProperties(
                true, secret, client, URI.create(url), Duration.ofSeconds(3), allow);
    }

    @Test
    void supportsBothTestKeyFamiliesButRejectsLiveAndMixedKeys() {
        var config = new TossConfig();
        var env = new MockEnvironment();
        assertThat(
                        config.tossHttpClient(
                                props(
                                        "test_gsk_synthetic",
                                        "test_gck_synthetic",
                                        "https://api.tosspayments.com",
                                        false),
                                env))
                .isNotNull();
        assertThat(
                        config.tossHttpClient(
                                props(
                                        "test_sk_synthetic",
                                        "test_ck_synthetic",
                                        "https://api.tosspayments.com",
                                        false),
                                env))
                .isNotNull();
        assertThatThrownBy(
                        () ->
                                config.tossHttpClient(
                                        props(
                                                "live_sk_synthetic",
                                                "live_ck_synthetic",
                                                "https://api.tosspayments.com",
                                                false),
                                        env))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                config.tossHttpClient(
                                        props(
                                                "test_gsk_synthetic",
                                                "test_ck_synthetic",
                                                "https://api.tosspayments.com",
                                                false),
                                        env))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fixtureHttpIsOnlyPermittedInIsolatedTestProfile() {
        var config = new TossConfig();
        var env = new MockEnvironment();
        var p = props("test_sk_synthetic", "test_ck_synthetic", "http://127.0.0.1:12345", true);
        assertThatThrownBy(() -> config.tossHttpClient(p, env))
                .isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("test");
        assertThat(config.tossHttpClient(p, env)).isNotNull();
        env.setActiveProfiles("test", "prod");
        assertThatThrownBy(() -> config.tossHttpClient(p, env))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                config.tossHttpClient(
                                        props(
                                                "test_sk_synthetic",
                                                "test_ck_synthetic",
                                                "https://untrusted.example",
                                                false),
                                        env))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void propertiesDoNotExposeKeysThroughToString() {
        assertThat(
                        props(
                                        "test_gsk_synthetic",
                                        "test_gck_synthetic",
                                        "https://api.tosspayments.com",
                                        false)
                                .toString())
                .doesNotContain("synthetic", "gsk", "gck");
    }
}
