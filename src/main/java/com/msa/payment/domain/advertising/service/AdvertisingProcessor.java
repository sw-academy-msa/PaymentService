package com.msa.payment.domain.advertising.service;

/** 임대와 외부 토스 호출 및 완료 저장을 순서대로 실행합니다. */
public interface AdvertisingProcessor {
    boolean processNext();
}
