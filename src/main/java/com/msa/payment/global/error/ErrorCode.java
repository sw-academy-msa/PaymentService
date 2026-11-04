package com.msa.payment.global.error;

import org.springframework.http.HttpStatus;

/** 공통 예외 처리기가 도메인 구분 없이 사용할 오류 정보의 계약입니다. */
public interface ErrorCode {

    HttpStatus status();

    String code();

    String message();
}
