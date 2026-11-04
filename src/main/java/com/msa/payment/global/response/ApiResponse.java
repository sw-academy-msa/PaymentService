package com.msa.payment.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;

/** 일반 JSON API의 성공 응답입니다. 오류 응답의 code·traceId·violations 계약은 별도로 유지합니다. */
@Builder(access = lombok.AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(boolean success, String code, String message, T data) {
    /** 컨트롤러가 응답 DTO와 성공 코드를 명시하여 공통 형식으로 반환합니다. */
    public static <T> ApiResponse<T> onSuccess(T response, SuccessCode successCode) {
        return ApiResponse.<T>builder()
                .success(true)
                .code(successCode.code())
                .message(successCode.message())
                .data(response)
                .build();
    }

    // 인증 응답도 담을 수 있으므로 완성된 응답과 빌더의 문자열 출력은 본문을 숨깁니다.
    @Override
    public String toString() {
        return "ApiResponse[REDACTED]";
    }

    /** 로깅 과정에서 내부 data의 토큰·개인정보가 출력되지 않도록 제한합니다. */
    private static class ApiResponseBuilder<T> {
        @Override
        public String toString() {
            return "ApiResponseBuilder[REDACTED]";
        }
    }
}
