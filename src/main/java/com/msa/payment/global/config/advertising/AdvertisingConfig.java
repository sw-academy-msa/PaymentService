package com.msa.payment.global.config.advertising;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 서비스 설정의 고정 주소만 귀환 주소로 사용합니다. */
@Configuration
@EnableConfigurationProperties(AdvertisingProperties.class)
public class AdvertisingConfig {}
