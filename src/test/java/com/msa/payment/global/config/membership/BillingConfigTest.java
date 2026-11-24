package com.msa.payment.global.config.membership;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;

/** 위젯/라이브 키와 짧은 타임아웃 및 임의 목적지를 빌링 호출에서 차단합니다. */
class BillingConfigTest {
    private final BillingConfig config = new BillingConfig();

    private BillingProperties props(
            String client, String secret, String url, int timeout, boolean allow) {
        return new BillingProperties(
                true, client, secret, URI.create(url), Duration.ofSeconds(timeout), allow);
    }

    @Test
    void onlyIndividualTestKeysAndSufficientTimeoutAreAccepted() {
        var env = new MockEnvironment();
        var valid =
                props(
                        "test_ck_fixture",
                        "test_sk_fixture",
                        "https://api.tosspayments.com",
                        65,
                        false);
        assertThat(config.billingHttpClient(valid, env).followRedirects())
                .isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
        for (var invalid :
                new BillingProperties[] {
                    props(
                            "test_gck_fixture",
                            "test_gsk_fixture",
                            "https://api.tosspayments.com",
                            65,
                            false),
                    props(
                            "live_ck_fixture",
                            "live_sk_fixture",
                            "https://api.tosspayments.com",
                            65,
                            false),
                    props(
                            "test_ck_fixture",
                            "test_sk_fixture",
                            "https://api.tosspayments.com",
                            3,
                            false),
                    props(
                            "test_ck_fixture",
                            "test_sk_fixture",
                            "https://untrusted.example",
                            65,
                            false)
                }) {
            assertThatThrownBy(() -> config.billingHttpClient(invalid, env))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(valid.toString()).doesNotContain("test_sk_fixture", "test_ck_fixture");
    }

    @Test
    void localHttpRequiresTestWithoutProdAndKeyRequiresThirtyTwoBytes() {
        var env = new MockEnvironment();
        var fixture =
                props("test_ck_fixture", "test_sk_fixture", "http://127.0.0.1:12345", 65, true);
        assertThatThrownBy(() -> config.billingHttpClient(fixture, env))
                .isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("test");
        assertThat(config.billingHttpClient(fixture, env)).isNotNull();
        env.setActiveProfiles("test", "prod");
        assertThatThrownBy(() -> config.billingHttpClient(fixture, env))
                .isInstanceOf(IllegalStateException.class);
        var key = new MembershipCryptoProperties(Base64.getEncoder().encodeToString(new byte[32]));
        assertThat(config.membershipDataKey(key).getEncoded()).hasSize(32);
        assertThat(key.toString()).contains("REDACTED");
        assertThatThrownBy(() -> config.membershipDataKey(new MembershipCryptoProperties("bad")))
                .isInstanceOf(IllegalStateException.class);
    }
}
