package com.msa.payment.domain.advertising.exception;

import com.msa.payment.global.error.BusinessException;

/** 기존 전역 오류 응답 형식을 유지합니다. */
public class AdvertisingException extends BusinessException {
    public AdvertisingException(AdvertisingErrorCode code) {
        super(code);
    }
}
