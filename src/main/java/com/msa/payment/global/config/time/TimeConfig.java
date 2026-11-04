package com.msa.payment.global.config.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 업무 시각을 서버 UTC Clock으로 통일하고 테스트에서 고정 시각으로 대체할 수 있게 합니다. */
@Configuration
public class TimeConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
