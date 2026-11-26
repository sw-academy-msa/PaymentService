package com.msa.payment.domain.advertising.exception;

import com.msa.payment.global.error.ErrorCode;

import org.springframework.http.HttpStatus;

/** 공급자 비밀값을 포함하지 않는 광고 결제 업무 오류입니다. */
public enum AdvertisingErrorCode implements ErrorCode {
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_AD_UNAVAILABLE", "광고 결제를 이용할 수 없습니다."),
    OWNERSHIP(HttpStatus.FORBIDDEN, "PAYMENT_AD_OWNERSHIP", "본인 광고 결제만 조회할 수 있습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_AD_NOT_FOUND", "광고 결제를 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "PAYMENT_AD_STATE_CONFLICT", "기존 결제 처리 상태를 확인해 주세요."),
    INVALID_COMMAND(HttpStatus.BAD_REQUEST, "PAYMENT_AD_COMMAND_INVALID", "광고 결제 명령이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AdvertisingErrorCode(HttpStatus status, String code, String message) {
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
