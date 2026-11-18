package com.msa.payment.domain.payment.service;

/** 영속 작업 하나를 실행하며 재시작·복제본 경합은 DB 임대와 공급자 멱등 키로 보호합니다. */
public interface PaymentProcessor {
    boolean processNext();
}
