package com.msa.payment.domain.analytics.client.impl;

import com.msa.payment.domain.analytics.client.InternalHttp;
import com.msa.payment.domain.analytics.client.RemoteUnavailable;
import com.msa.payment.domain.analytics.dto.response.InternalReply;
import com.msa.payment.global.config.client.ClientProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Eureka 서비스 ID로만 내부 호출을 보내 인스턴스 주소를 설정에 두지 않습니다. */
@Component
public class InternalHttpImpl implements InternalHttp {
    private final RestClient rest;
    private final String scheme;

    public InternalHttpImpl(
            @Qualifier("loadBalancedRestClient") RestClient rest, ClientProperties properties) {
        this.rest = rest;
        this.scheme = properties.scheme();
    }

    public InternalReply send(
            String service,
            String method,
            String pathAndQuery,
            Map<String, String> headers,
            String body) {
        if (service == null || !service.matches("[a-z][a-z0-9-]{1,99}")) {
            throw new RemoteUnavailable();
        }
        try {
            var request =
                    rest.method(HttpMethod.valueOf(method))
                            .uri(URI.create(scheme + "://" + service + pathAndQuery))
                            .headers(h -> headers.forEach(h::set));
            if (body != null) request.body(body);
            return request.exchange(
                    (req, response) ->
                            new InternalReply(
                                    response.getStatusCode().value(),
                                    new String(
                                            response.getBody().readAllBytes(),
                                            StandardCharsets.UTF_8)));
        } catch (RuntimeException ex) {
            // 인스턴스 없음(IllegalStateException)과 재시도 후에도 남은 연결 실패를 모두 확인 불가로 다룹니다.
            throw new RemoteUnavailable();
        }
    }
}
