package com.msa.payment.domain.analytics.service;

/** 한 번의 임대와 전송 및 결과 저장을 조율합니다. */
public interface GaProcessor {
    boolean processNext();
}
