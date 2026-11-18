package com.msa.payment.global.error;

import java.util.List;

/** 클라이언트에 전달하는 공통 오류 형식입니다. traceId로 서버 로그를 연결하고 violations로 입력 오류를 안내합니다. */
public record ErrorResponse(
        String code, String message, String traceId, List<FieldViolation> violations) {
    // 입력 필드 오류가 없는 일반 오류 응답을 생성합니다.
    public static ErrorResponse of(ErrorCode errorCode, String traceId) {
        return new ErrorResponse(errorCode.code(), errorCode.message(), traceId, List.of());
    }

    // 잘못된 필드 이름과 사유를 포함하는 검증 오류 응답을 생성합니다.
    public static ErrorResponse validation(String traceId, List<FieldViolation> violations) {
        return new ErrorResponse(
                CommonErrorCode.INVALID_REQUEST.code(),
                CommonErrorCode.INVALID_REQUEST.message(),
                traceId,
                violations);
    }

    public record FieldViolation(String field, String message) {}
}
