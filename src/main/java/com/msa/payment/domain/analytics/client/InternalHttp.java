package com.msa.payment.domain.analytics.client;

import com.msa.payment.domain.analytics.dto.response.InternalReply;

import java.util.Map;

/** 내부 서비스 호출을 Eureka 서비스 ID로만 보냅니다. 인스턴스 선택·상태 확인·연결 실패 재시도는 LoadBalancer가 담당합니다. */
public interface InternalHttp {
    /** 응답을 받으면 상태와 관계없이 돌려줍니다. 전송 자체가 실패하면 동의 거절이 아닌 재시도 신호(RemoteUnavailable)를 던집니다. */
    InternalReply send(
            String service,
            String method,
            String pathAndQuery,
            Map<String, String> headers,
            String body);
}
