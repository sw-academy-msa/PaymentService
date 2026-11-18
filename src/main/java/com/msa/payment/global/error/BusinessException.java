package com.msa.payment.global.error;

/** 예상 가능한 업무 오류를 표현합니다. HTTP 응답에 필요한 정보는 ErrorCode로 전달합니다. */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    // 공통 예외 처리기가 사용할 업무 오류 정의를 반환합니다.
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
