package com.msa.payment.global.config.logging;

import com.msa.payment.global.logging.RequestTraceFilter;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** 서블릿 오류 재디스패치에도 최초 요청의 추적 ID를 유지하도록 필터를 등록합니다. */
@Configuration
public class TraceFilterConfig {
    @Bean
    public FilterRegistrationBean<RequestTraceFilter> requestTraceRegistration(
            RequestTraceFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(
                DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
