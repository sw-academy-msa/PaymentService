package com.msa.payment.global.config.membership;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.URI;

/** 회원제 업무 플래그와 고정 가격 설정을 기존 설정 바인딩으로 제공합니다. */
@Configuration
@EnableConfigurationProperties(MembershipProperties.class)
public class MembershipConfig {
    public MembershipConfig(MembershipProperties properties, Environment environment) {
        if (properties.amount() < 100
                || properties.planVersion() == null
                || properties.planVersion().isBlank()
                || properties.planVersion().length() > 40
                || properties.termsVersion() == null
                || properties.termsVersion().isBlank()
                || properties.termsVersion().length() > 40
                || properties.providerKeyVersion() == null
                || !properties.providerKeyVersion().matches("[A-Za-z0-9_-]{1,40}"))
            throw new IllegalStateException("멤버십 요금과 약관 및 공급자 키 버전을 확인해 주세요.");
        boolean local = environment.acceptsProfiles(Profiles.of("local & !prod"));
        validate(properties.successUrl(), "success", local);
        validate(properties.failUrl(), "fail", local);
    }

    private void validate(URI uri, String result, boolean local) {
        // 프론트는 HashRouter라 해시 경로로 돌아와야 정적 rewrite 없이 화면이 받습니다.
        URI production =
                URI.create("https://stockdrop-app.vercel.app/#/membership/billing/" + result);
        URI development = URI.create("http://localhost:5179/#/membership/billing/" + result);
        if (!production.equals(uri) && !(local && development.equals(uri)))
            throw new IllegalStateException("멤버십 카드 인증 redirect가 허용 목록과 일치하지 않습니다.");
    }
}
