package com.msa.payment.global.error;

import org.springframework.http.HttpStatus;

/** 여러 도메인에서 공유하는 요청 오류와 서버 오류를 정의합니다. */
public enum CommonErrorCode implements ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_INVALID_REQUEST", "요청값이 올바르지 않습니다."),
    INVALID_REQUEST_BODY(
            HttpStatus.BAD_REQUEST, "COMMON_INVALID_REQUEST_BODY", "요청 본문을 읽을 수 없습니다."),
    INVALID_PARAMETER_TYPE(
            HttpStatus.BAD_REQUEST, "COMMON_INVALID_PARAMETER_TYPE", "요청값의 형식이 올바르지 않습니다."),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "COMMON_MISSING_PARAMETER", "필수 요청값이 누락되었습니다."),
    MISSING_HEADER(HttpStatus.BAD_REQUEST, "COMMON_MISSING_HEADER", "필수 요청 헤더가 누락되었습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_UNAUTHORIZED", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_FORBIDDEN", "요청을 수행할 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED, "COMMON_METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "COMMON_NOT_ACCEPTABLE", "요청한 응답 형식을 제공할 수 없습니다."),
    DATA_CONFLICT(HttpStatus.CONFLICT, "COMMON_DATA_CONFLICT", "현재 데이터 상태와 요청이 충돌합니다."),
    OPTIMISTIC_LOCK_CONFLICT(
            HttpStatus.CONFLICT, "COMMON_OPTIMISTIC_LOCK_CONFLICT", "다른 요청이 먼저 데이터를 변경했습니다."),
    UNSUPPORTED_MEDIA_TYPE(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "COMMON_UNSUPPORTED_MEDIA_TYPE",
            "지원하지 않는 Content-Type입니다."),
    PAYLOAD_TOO_LARGE(
            HttpStatus.CONTENT_TOO_LARGE, "COMMON_PAYLOAD_TOO_LARGE", "요청 파일의 크기가 허용 범위를 초과했습니다."),
    SERVICE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "COMMON_SERVICE_UNAVAILABLE", "결제 서비스를 사용할 수 없습니다."),
    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    CommonErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    // 클라이언트에 반환할 HTTP 상태를 제공합니다.
    public HttpStatus status() {
        return status;
    }

    @Override
    // 클라이언트가 오류를 구분하는 고정 식별자를 제공합니다.
    public String code() {
        return code;
    }

    @Override
    // 사용자에게 전달할 오류 설명을 제공합니다.
    public String message() {
        return message;
    }
}
