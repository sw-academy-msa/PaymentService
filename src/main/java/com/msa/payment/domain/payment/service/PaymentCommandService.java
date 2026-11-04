package com.msa.payment.domain.payment.service;

/** Kafka 명령을 로컬 원본·처리 이력과 원자 저장하며 소비 스레드에서 토스를 호출하지 않습니다. */
public interface PaymentCommandService {
    void receive(String key, byte[] payload);
}
