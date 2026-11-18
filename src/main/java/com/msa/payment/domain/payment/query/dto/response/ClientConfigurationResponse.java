package com.msa.payment.domain.payment.query.dto.response;

import lombok.Builder;

/** 브라우저 SDK 초기화에 필요한 공개 클라이언트 키만 반환하며 시크릿 키는 포함하지 않습니다. */
@Builder
public record ClientConfigurationResponse(String clientKey) {
    /** 빌더에 담긴 개인정보·결제키·토큰·이벤트 본문을 로그에 노출하지 않습니다. */
    public static class ClientConfigurationResponseBuilder {
        @Override
        public String toString() {
            return "ClientConfigurationResponseBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "ClientConfigurationResponse[clientKey=public-key-redacted]";
    }
}
