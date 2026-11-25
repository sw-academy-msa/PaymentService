package com.msa.payment.domain.membership.exception;

import com.msa.payment.global.error.ErrorCode;

import org.springframework.http.HttpStatus;

/** 공급자 응답이나 카드 정보를 포함하지 않는 멤버십 업무 오류입니다. */
public enum MembershipErrorCode implements ErrorCode {
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MEMBERSHIP_UNAVAILABLE", "멤버십을 이용할 수 없습니다."),
    INVALID_CONSENT(
            HttpStatus.BAD_REQUEST, "MEMBERSHIP_CONSENT_INVALID", "현재 요금과 정기결제 약관에 동의해야 합니다."),
    CONFLICT(HttpStatus.CONFLICT, "MEMBERSHIP_CONFLICT", "기존 구독 또는 처리 중인 작업을 확인해 주세요."),
    INVALID_SESSION(HttpStatus.BAD_REQUEST, "MEMBERSHIP_SESSION_INVALID", "인증 세션을 확인해 주세요."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "MEMBERSHIP_FORBIDDEN", "본인 계정으로만 요청할 수 있습니다."),
    INVALID_COMMAND(HttpStatus.BAD_REQUEST, "MEMBERSHIP_COMMAND_INVALID", "멤버십 명령이 올바르지 않습니다.");
    private final HttpStatus status;
    private final String code;
    private final String message;

    MembershipErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
