package com.msa.payment.domain.payment.exception;

import com.msa.payment.global.error.CommonErrorCode;
import com.msa.payment.global.error.ErrorCode;

import org.springframework.http.HttpStatus;

/** 결제 업무 오류입니다. 기존 공개 오류 응답과 Kafka 영구 계약 오류 메시지를 유지하고 키·본문은 포함하지 않습니다. */
public enum PaymentErrorCode implements ErrorCode {
    SERVICE_UNAVAILABLE(CommonErrorCode.SERVICE_UNAVAILABLE),
    INVALID_COMMAND(HttpStatus.BAD_REQUEST, "PAYMENT_COMMAND_INVALID", "PAYMENT_COMMAND_INVALID");

    private final HttpStatus status;
    private final String code;
    private final String message;

    PaymentErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    /** 비활성 결제 설정 조회의 기존 COMMON_SERVICE_UNAVAILABLE 계약을 재사용합니다. */
    PaymentErrorCode(CommonErrorCode errorCode) {
        this(errorCode.status(), errorCode.code(), errorCode.message());
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
